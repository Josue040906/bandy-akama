package com.bandyakama.bench;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.ConversationService;
import com.bandyakama.executor.ToolExecutor;
import com.bandyakama.observability.PerformanceTelemetry;
import com.bandyakama.security.UserIdentity;
import com.bandyakama.tool.ToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;

/**
 * Banc de conversation contre le vrai modèle Gemini.
 *
 * Les outils métier sont remplacés par des réponses fictives : le banc mesure
 * ce que Gemini comprend (outil choisi, arguments, suivi de conversation),
 * sans appeler l'API RH. Il ne s'exécute que sur demande explicite
 * (-Dbench=true), jamais pendant un simple « mvn test ».
 * Il ne fait échouer le build que sur une erreur technique : les écarts aux
 * attentes sont des mesures, écrites dans target/bench/.
 */
@EnabledIfSystemProperty(named = "bench", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${BANDY_BENCH_DB_URL}",
        "spring.datasource.username=${BANDY_BENCH_DB_USERNAME:postgres}",
        "spring.datasource.password=${BANDY_BENCH_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "bandy.ai.model=${GEMINI_MODEL:gemini-3.5-flash-lite}"
})
class ConversationBenchTest {
    private static final UserIdentity BENCH_USER =
            new UserIdentity("bench", "bandy-bench");

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ObjectMapper mapper;

    @MockBean
    private ToolExecutor toolExecutor;

    @SpyBean
    private PerformanceTelemetry telemetry;

    @Value("${GEMINI_API_KEY:}")
    private String apiKey;

    @Value("${bandy.ai.model:gemini-3.5-flash-lite}")
    private String model;

    @Value("${bandy.ai.max-tool-rounds:3}")
    private int maxToolRounds;

    private final List<ToolCall> calls = new ArrayList<>();
    private final List<Long> modelCallTimes = new ArrayList<>();

    /** S'exécute avant le démarrage de Spring : nomme les variables absentes du terminal. */
    @BeforeAll
    static void requireConfiguration() {
        boolean dryRun = "true".equalsIgnoreCase(System.getenv("BANDY_BENCH_DRY_RUN"));
        // Le mot de passe reste facultatif : une base locale en « trust » n'en demande pas.
        List<String> missing = (dryRun
                ? List.of("BANDY_BENCH_DB_URL")
                : List.of("BANDY_BENCH_DB_URL", "GEMINI_API_KEY"))
                .stream()
                .filter(name -> System.getenv(name) == null || System.getenv(name).isBlank())
                .toList();
        if (!missing.isEmpty()) {
            fail("Variables absentes de ce terminal pour le banc : " + missing
                    + ". Définissez-les puis relancez (aucun appel Gemini n'a été fait).");
        }
    }

    @Test
    void runsScenarios() throws Exception {
        List<Scenario> allScenarios = mapper.readValue(
                resource("bench/scenarios.json"),
                new TypeReference<>() { }
        );
        // Facultatif : BANDY_BENCH_SCENARIOS=S18,S19 ne mesure que ces scénarios.
        List<String> selected = List.of(
                System.getenv().getOrDefault("BANDY_BENCH_SCENARIOS", "").split("\\s*,\\s*")
        ).stream().filter(id -> !id.isBlank()).toList();
        List<Scenario> scenarios = selected.isEmpty()
                ? allScenarios
                : allScenarios.stream().filter(s -> selected.contains(s.id())).toList();
        JsonNode toolResults = mapper.readTree(resource("bench/tool-results.json"));
        int maxCalls = Integer.parseInt(
                System.getenv().getOrDefault("BANDY_BENCH_MAX_CALLS", "50")
        );
        // Le palier gratuit de Gemini refuse au-delà de 15 requêtes par minute.
        int callsPerMinute = Integer.parseInt(
                System.getenv().getOrDefault("BANDY_BENCH_CALLS_PER_MINUTE", "12")
        );
        List<Turn> allTurns = scenarios.stream()
                .flatMap(scenario -> scenario.tours().stream())
                .toList();
        int estimate = allTurns.stream()
                .mapToInt(turn -> "aucun".equals(turn.outil()) ? 1 : 2)
                .sum();
        System.out.printf(
                "Banc : %d scénarios, %d tours, environ %d appels Gemini, plafond %d, "
                        + "au plus %d appels par minute (modèle %s).%n",
                scenarios.size(), allTurns.size(), estimate, maxCalls, callsPerMinute, model
        );
        if ("true".equalsIgnoreCase(System.getenv("BANDY_BENCH_DRY_RUN"))) {
            return;
        }

        doAnswer(invocation -> {
            String name = invocation.<ToolDefinition>getArgument(0).getName();
            Map<String, Object> arguments =
                    new LinkedHashMap<>(invocation.<Map<String, Object>>getArgument(1));
            calls.add(new ToolCall(name, arguments));
            JsonNode fixture = toolResults.get(name);
            if (fixture == null) {
                return Map.of("message", "Outil inconnu du banc.");
            }
            if (fixture.has("agents") && arguments.get("criteriaJson") instanceof String criteria) {
                return searchResult(
                        fixture.get("agents"), criteria, "COUNT".equals(arguments.get("resultMode"))
                );
            }
            // Une route à opérations (operation=...) a une réponse fictive par opération.
            if (arguments.get("operation") instanceof String operation && fixture.has("operations")) {
                JsonNode reponse = fixture.get("operations").get(operation);
                return reponse == null
                        ? Map.of("message", "Opération d'organisation inconnue : " + operation)
                        : mapper.convertValue(reponse, Object.class);
            }
            return mapper.convertValue(fixture, Object.class);
        }).when(toolExecutor).execute(
                any(), anyMap(), nullable(String.class), nullable(String.class)
        );
        // Navigation et tâche préparée ne font aucune requête : l'appel est
        // enregistré, puis la vraie méthode s'exécute, comme en production.
        doAnswer(invocation -> {
            calls.add(new ToolCall(
                    invocation.<ToolDefinition>getArgument(0).getName(),
                    new LinkedHashMap<>(invocation.<Map<String, Object>>getArgument(1))
            ));
            return invocation.callRealMethod();
        }).when(toolExecutor).interfaceAction(any(), anyMap());
        doAnswer(invocation -> {
            modelCallTimes.add(System.nanoTime());
            return invocation.callRealMethod();
        }).when(telemetry).modelCall(
                any(), anyInt(), any(), any(), any(), anyLong(), anyBoolean()
        );

        int worstCasePerTurn = maxToolRounds + 2;
        StringBuilder table = new StringBuilder(
                "| Tour | Message | Outils appelés | Arguments | Appels | Verdict |\n"
                        + "|---|---|---|---|---|---|\n");
        StringBuilder answers = new StringBuilder();
        int ok = 0;
        int failed = 0;
        int technical = 0;
        int unmeasurable = 0;
        int skipped = 0;
        for (Scenario scenario : scenarios) {
            UUID conversationId = conversations.create(BENCH_USER).id();
            boolean previousTurnFailed = false;
            for (int index = 0; index < scenario.tours().size(); index++) {
                Turn turn = scenario.tours().get(index);
                String label = scenario.id() + "." + (index + 1);
                if (previousTurnFailed) {
                    // Un suivi n'a pas de sens si le tour qu'il prolonge a échoué.
                    unmeasurable++;
                    table.append(row(label, turn.message(), "", "", 0,
                            "NON MESURABLE (tour précédent en erreur)"));
                    continue;
                }
                if (modelCalls() + worstCasePerTurn > maxCalls) {
                    skipped++;
                    table.append(row(label, turn.message(), "", "", 0,
                            "NON EXÉCUTÉ (plafond)"));
                    continue;
                }
                waitForRateLimit(worstCasePerTurn, callsPerMinute);
                int callsBefore = modelCalls();
                calls.clear();
                String answer = "";
                String verdict;
                try {
                    ChatResponse response = conversations.sendMessage(
                            conversationId, BENCH_USER, turn.message(),
                            "Bearer banc", UUID.randomUUID()
                    );
                    answer = response.message() == null ? "" : response.message();
                    List<String> problems = check(turn, answer);
                    verdict = problems.isEmpty()
                            ? (turn.relire() ? "À RELIRE" : "OK")
                            : "ÉCHEC : " + String.join(" ; ", problems);
                } catch (RuntimeException failure) {
                    verdict = "ERREUR TECHNIQUE : " + failure.getMessage();
                }
                if (verdict.startsWith("ERREUR")) {
                    technical++;
                    previousTurnFailed = true;
                } else if (verdict.startsWith("ÉCHEC")) {
                    failed++;
                } else {
                    ok++;
                }
                table.append(row(
                        label, turn.message(),
                        String.join(", ", calls.stream().map(ToolCall::name).toList()),
                        calls.stream().map(call -> json(call.arguments()))
                                .reduce((a, b) -> a + " / " + b).orElse(""),
                        modelCalls() - callsBefore, verdict
                ));
                answers.append("- **").append(label).append("** ")
                        .append(turn.message()).append("\n  > ")
                        .append(truncate(answer, 400).replace("\n", " ")).append("\n");
            }
        }

        String summary = String.format(
                "%d OK, %d échecs de compréhension, %d erreurs techniques, "
                        + "%d non mesurables, %d non exécutés, %d appels Gemini (modèle %s).",
                ok, failed, technical, unmeasurable, skipped, modelCalls(), model
        );
        Path report = Path.of("target", "bench", "rapport-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + ".md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "# Banc de conversation\n\n" + summary + "\n\n"
                + table + "\n## Réponses\n\n" + answers, StandardCharsets.UTF_8);
        System.out.println(summary);
        System.out.println("Rapport : " + report.toAbsolutePath());
    }

    private List<String> check(Turn turn, String answer) {
        List<String> problems = new ArrayList<>();
        boolean expectsNoTool = "aucun".equals(turn.outil());
        List<ToolCall> relevant = turn.outil() == null || expectsNoTool
                ? calls
                : calls.stream().filter(call -> call.name().equals(turn.outil())).toList();
        if (expectsNoTool && !calls.isEmpty()) {
            problems.add("outil inattendu " + calls.stream().map(ToolCall::name).toList());
        } else if (turn.outil() != null && !expectsNoTool && relevant.isEmpty()) {
            problems.add("outil attendu " + turn.outil() + ", obtenu "
                    + calls.stream().map(ToolCall::name).toList());
        }
        List<String[]> pairs = new ArrayList<>();
        relevant.forEach(call -> call.arguments().forEach((key, value) -> {
            if ("criteriaJson".equals(key) && value instanceof String text) {
                try {
                    collectPairs(mapper.readTree(text), pairs);
                } catch (Exception invalidJson) {
                    pairs.add(new String[]{key, text});
                }
            } else {
                pairs.add(new String[]{key, String.valueOf(value)});
            }
        }));
        turn.attendus().stream()
                .filter(rule -> pairs.stream().noneMatch(pair -> matches(rule, pair)))
                .forEach(rule -> problems.add("critère absent " + rule));
        turn.interdits().stream()
                .filter(rule -> pairs.stream().anyMatch(pair -> matches(rule, pair)))
                .forEach(rule -> problems.add("critère interdit présent " + rule));
        turn.reponseContient().stream()
                .filter(text -> !normalize(answer).contains(normalize(text)))
                .forEach(text -> problems.add("réponse sans « " + text + " »"));
        return problems;
    }

    /** Une feuille {field, value} devient (field, value) ; une clé scalaire devient (clé, valeur). */
    private static void collectPairs(JsonNode node, List<String[]> pairs) {
        if (node.isObject()) {
            if (node.hasNonNull("field")) {
                JsonNode value = node.path("value");
                pairs.add(new String[]{node.get("field").asText(),
                        value.isArray() ? value.toString() : value.asText()});
            }
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isValueNode()
                        && !List.of("field", "value", "operator").contains(entry.getKey())) {
                    pairs.add(new String[]{entry.getKey(), entry.getValue().asText()});
                }
                collectPairs(entry.getValue(), pairs);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectPairs(child, pairs));
        }
    }

    /** Règles : « clé » (présente), « clé=valeur » (égale), « clé~texte » (contient) ; « * » = toute clé. */
    private static boolean matches(String rule, String[] pair) {
        int equals = rule.indexOf('=');
        int contains = rule.indexOf('~');
        int operator = equals < 0 ? contains : contains < 0 ? equals : Math.min(equals, contains);
        String key = operator < 0 ? rule : rule.substring(0, operator);
        if (!"*".equals(key) && !normalize(key).equals(normalize(pair[0]))) {
            return false;
        }
        if (operator < 0) {
            return true;
        }
        String expected = normalize(rule.substring(operator + 1));
        String actual = normalize(pair[1]);
        return rule.charAt(operator) == '=' ? actual.equals(expected) : actual.contains(expected);
    }

    /**
     * Imite la recherche RH sur les agents fictifs : applique les critères
     * must / must_not / should, puis la pagination ou le comptage.
     * Un champ absent des données fictives ne filtre pas.
     */
    private Map<String, Object> searchResult(JsonNode agents, String criteriaJson, boolean count) {
        JsonNode criteria;
        try {
            criteria = mapper.readTree(criteriaJson);
        } catch (Exception invalidJson) {
            throw new IllegalArgumentException("criteriaJson n'est pas un JSON valide.");
        }
        boolean hasPositiveCriterion = !criteria.path("must").isEmpty()
                || !criteria.path("filters").isEmpty()
                || (criteria.path("minimum_should_match").asInt(0) >= 1
                && !criteria.path("should").isEmpty());
        if (!hasPositiveCriterion) {
            // Comme l'API : une recherche sans critère positif est refusée.
            throw org.springframework.web.client.HttpClientErrorException.create(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    org.springframework.http.HttpHeaders.EMPTY,
                    "{\"message\":\"Au moins un critère de recherche est requis.\"}"
                            .getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8
            );
        }
        List<JsonNode> matching = new ArrayList<>();
        agents.forEach(agent -> {
            if (matchesGroup(agent, criteria)) {
                matching.add(agent);
            }
        });
        Map<String, Object> result = new LinkedHashMap<>();
        if (count) {
            result.put("total", matching.size());
            result.put("agents", List.of());
            return result;
        }
        int limit = criteria.path("limit").asInt(20);
        int offset = criteria.path("offset").asInt(0);
        List<JsonNode> page = matching.subList(
                Math.min(offset, matching.size()), Math.min(offset + limit, matching.size())
        );
        // Même contrat que l'API : total = toutes les correspondances, pas la page.
        result.put("total", matching.size());
        result.put("limit", limit);
        result.put("offset", offset);
        result.put("hasMore", offset + limit < matching.size());
        result.put("agents", mapper.convertValue(page, List.class));
        return result;
    }

    private static boolean matchesGroup(JsonNode agent, JsonNode group) {
        for (String key : List.of("must", "filters")) {
            for (JsonNode condition : group.path(key)) {
                if (!matchesCondition(agent, condition)) {
                    return false;
                }
            }
        }
        for (JsonNode condition : group.path("must_not")) {
            if (matchesCondition(agent, condition)) {
                return false;
            }
        }
        JsonNode should = group.path("should");
        if (group.path("minimum_should_match").asInt(0) >= 1 && !should.isEmpty()) {
            for (JsonNode condition : should) {
                if (matchesCondition(agent, condition)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean matchesCondition(JsonNode agent, JsonNode condition) {
        if (condition.has("bool")) {
            return matchesGroup(agent, condition.get("bool"));
        }
        String field = condition.path("field").asText();
        String operator = condition.path("operator").asText();
        JsonNode value = condition.path("value");
        JsonNode actual = List.of("personne", "nom_complet").contains(field)
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode(
                        agent.path("prenom").asText() + " " + agent.path("nom").asText())
                : agent.path(field);
        if (actual.isMissingNode()) {
            return true;
        }
        if (actual.isNumber()) {
            double number = actual.asDouble();
            double expected = value.asDouble();
            double upper = condition.path("upperValue").asDouble(expected);
            return switch (operator) {
                case "GT" -> number > expected;
                case "GTE" -> number >= expected;
                case "LT" -> number < expected;
                case "LTE" -> number <= expected;
                case "BETWEEN" -> number >= expected && number <= upper;
                case "NE" -> number != expected;
                default -> number == expected;
            };
        }
        String text = normalize(actual.asText());
        String expected = normalize(value.asText());
        return switch (operator) {
            case "EQ" -> text.equals(expected);
            case "NE" -> !text.equals(expected);
            case "FUZZY_PERSON" -> java.util.Arrays.stream(expected.split("\\s+"))
                    .allMatch(text::contains);
            default -> text.contains(expected);
        };
    }

    private int modelCalls() {
        return modelCallTimes.size();
    }

    /** Attend que le pire cas d'un tour tienne dans la fenêtre glissante d'une minute. */
    private void waitForRateLimit(int callsNeeded, int callsPerMinute)
            throws InterruptedException {
        while (modelCallTimes.stream()
                .filter(time -> System.nanoTime() - time < 60_000_000_000L)
                .count() + callsNeeded > callsPerMinute) {
            Thread.sleep(1_000);
        }
    }

    private String json(Object value) {
        try {
            return truncate(mapper.writeValueAsString(value), 220);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }

    private static String row(String label, String message, String tools,
                              String arguments, int modelCalls, String verdict) {
        return "| " + String.join(" | ", label, cell(message), cell(tools), cell(arguments),
                String.valueOf(modelCalls), cell(verdict)) + " |\n";
    }

    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }

    private static InputStream resource(String path) {
        return ConversationBenchTest.class.getClassLoader().getResourceAsStream(path);
    }

    private record ToolCall(String name, Map<String, Object> arguments) { }

    private record Scenario(String id, String titre, List<Turn> tours) { }

    private record Turn(
            String message,
            String outil,
            List<String> attendus,
            List<String> interdits,
            List<String> reponseContient,
            boolean relire
    ) {
        private Turn {
            attendus = attendus == null ? List.of() : attendus;
            interdits = interdits == null ? List.of() : interdits;
            reponseContient = reponseContient == null ? List.of() : reponseContient;
        }
    }
}
