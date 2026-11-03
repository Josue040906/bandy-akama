package com.bandyakama.conversation.dialogue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationPlanJsonParserTest {
    private final ConversationPlanJsonParser parser =
            new ConversationPlanJsonParser(new ObjectMapper());

    @Test
    void parsesMultipleOperationsDependenciesAndLocalizedClarification() {
        String json = """
                {
                  "rationale": "Deux objectifs indépendants et une référence ambiguë.",
                  "confidence": "HIGH",
                  "operations": [
                    {
                      "operationId": "report",
                      "type": "CREATE_OBJECTIVE",
                      "targetObjectiveId": null,
                      "goal": "Préparer un rapport",
                      "summary": "Rapport de ventes",
                      "parent": null,
                      "dependsOn": [],
                      "referenceMentions": []
                    },
                    {
                      "operationId": "compare",
                      "type": "CREATE_SUBTASK",
                      "targetObjectiveId": null,
                      "goal": "Comparer avec l'année précédente",
                      "summary": "Comparaison annuelle",
                      "parent": {"scope": "PROPOSED_OPERATION", "id": "report"},
                      "dependsOn": [],
                      "referenceMentions": [{"label": "année dernière", "kind": "PERIOD"}]
                    },
                    {
                      "operationId": "travel",
                      "type": "CREATE_OBJECTIVE",
                      "targetObjectiveId": null,
                      "goal": "Préparer un voyage",
                      "summary": "Plan de voyage",
                      "parent": null,
                      "dependsOn": [],
                      "referenceMentions": []
                    }
                  ],
                  "clarifications": [
                    {
                      "clarificationId": "clarify-1",
                      "target": null,
                      "question": "De quel élément parlez-vous ?",
                      "rationale": "La référence est ambiguë."
                    }
                  ]
                }
                """;

        ConversationPlanProposal proposal = parser.parse(json);

        assertEquals(3, proposal.operations().size());
        assertEquals(1, proposal.clarifications().size());
        assertEquals("report", proposal.operations().get(1).parent().id());
        assertEquals("travel", proposal.operations().get(2).operationId());
    }

    @Test
    void rejectsMalformedOrStructurallyIncompleteJson() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                {"rationale":"x","confidence":"HIGH","operations":[]}
                """));
    }

    @Test
    void rejectsUnknownEnumsAndWrongFieldTypes() {
        String unknownEnum = """
                {
                  "rationale":"x","confidence":"HIGH","operations":[{
                    "operationId":"a","type":"DO_MAGIC","targetObjectiveId":null,
                    "goal":"x","summary":"x","parent":null,"dependsOn":[],
                    "referenceMentions":[]
                  }],"clarifications":[]
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> parser.parse(unknownEnum));

        String wrongType = """
                {
                  "rationale":"x","confidence":"HIGH","operations":[{
                    "operationId":"a","type":"CREATE_OBJECTIVE","targetObjectiveId":null,
                    "goal":42,"summary":"x","parent":null,"dependsOn":[],
                    "referenceMentions":[]
                  }],"clarifications":[]
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> parser.parse(wrongType));
    }
}
