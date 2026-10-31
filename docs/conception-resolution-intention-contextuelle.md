# Conception — résolution contextuelle des intentions

## Objectif

Remplacer la classification des intentions fondée sur des mots-clés et expressions régulières par une interprétation contextuelle produite par Gemini. Java conserve la validation, l'autorisation, l'exécution et la persistance.

## Principes non négociables

1. Gemini interprète le message courant à partir de l'historique pertinent et de l'état structuré disponible.
2. Java ne déduit jamais une continuation ou un changement de sujet à partir du premier mot, d'un pronom ou d'une expression isolée.
3. Java valide les décisions et arguments de Gemini avant tout appel métier.
4. Les arguments transmis à un outil sont complets pour l'action courante. Aucun ancien filtre n'est fusionné implicitement.
5. Les identifiants d'entités servent à résoudre les références conversationnelles. Ils ne remplacent jamais les contrôles d'accès du backend RH.
6. Une ambiguïté qui peut changer l'entité ou les données consultées provoque une demande de précision, pas une sélection arbitraire.
7. Les résultats métier restent la source de vérité. Le modèle ne fabrique jamais d'identifiant, de chiffre ou de relation hiérarchique.

## Contrat d'interprétation

Gemini doit produire une décision structurée via une fonction interne `resolve_conversation_turn` :

- `intentType` : `CONTINUATION`, `NEW_INTENT` ou `DIRECT_RESPONSE`.
- `toolName` : nom d'un outil autorisé, absent pour une réponse directe ou une clarification.
- `operation` : opération métier, si l'outil en utilise une.
- `arguments` : objet complet des arguments de l'action courante.
- `referencedEntity` : référence facultative à une entité déjà identifiée (`type`, `id`, `name`, et parent éventuel).
- `needsClarification` : indique si la demande ne peut pas être exécutée sans précision.
- `clarificationQuestion` : question à poser si la clarification est nécessaire.
- `directResponse` : réponse directe facultative lorsque l'action ne nécessite aucun outil.

La fonction interne n'est pas un outil métier enregistré dans la base. Elle est une frontière technique entre l'interprétation du langage et l'exécution contrôlée.

## Exposition des outils métier

L'exposition à Gemini est contrôlée par deux indicateurs distincts dans `bandy_tool` :

- `enabled` autorise ou désactive l'exécution de l'outil dans l'application.
- `assistant_enabled` décide si l'outil est exposé au modèle pour la sélection d'une action.
- `sensitive` décide si le résultat métier peut être conservé dans les références persistantes.

Le catalogue, les descriptions, les paramètres, les opérations et les règles d'exposition proviennent de la base. Java ne maintient pas de liste métier fixe des noms d'outils autorisés. Les outils historiques non exposés restent enregistrés mais ne sont pas présentés à Gemini.

Après application de la migration V5, le script `scripts/register-rh-tools.ps1` doit être relancé pour enregistrer et vérifier `assistantEnabled=true` sur les outils RH gérés.
## Flux cible

1. ConversationService charge l'historique, le résumé, l'état structuré et les références précédentes, sans classer le message.
2. Gemini choisit de répondre directement ou d'appeler `resolve_conversation_turn`.
3. Java valide le contrat :
   - type d'intention connu ;
   - outil présent dans la liste autorisée et activée ;
   - opération cohérente avec l'outil ;
   - arguments déclarés, complets et de types corrects ;
   - clarification traitée avant toute exécution ;
   - références d'entités cohérentes avec le contexte et les résultats disponibles.
4. Si une précision est nécessaire, Bandy pose la question et ne lance aucun outil.
5. Si l'intention est une action, Java exécute l'outil métier via ToolExecutor.
6. Gemini formule la réponse finale uniquement à partir du résultat réel.
7. La décision et la référence d'entité utiles sont enregistrées dans l'état conversationnel, sans recopier inutilement les résultats RH sensibles.

## Coût des appels Gemini

Le flux doit préserver le coût normal d'une demande métier : un appel pour l'interprétation structurée, puis un appel de synthèse après l'exécution. Une réponse directe ou une question de clarification ne doit pas déclencher un second appel inutile.

La fonction interne sera exposée seule pendant l'étape d'interprétation. Les outils métier ne seront pas directement exécutables par Gemini à cette étape : Java les déclenchera après validation du contrat. Cela empêche le contournement de la décision structurée.

## État conversationnel et entités

L'état actif doit pouvoir conserver une référence explicite à l'entité sélectionnée, indépendante du dernier nom d'outil :

```json
{
  "activeEntity": {
    "type": "DIRECTION",
    "id": 1,
    "name": "Direction Générale de l'Administration",
    "parentType": null,
    "parentId": null
  }
}
```

Une référence n'est enregistrée que si elle est appuyée par un résultat métier ou un identifiant vérifié. Les résultats RH sensibles ne doivent pas être copiés dans les références de contexte. L'audit de la reconstruction mémoire et des métadonnées des messages doit précéder tout changement de persistance.

## Fusion des arguments

Les anciens arguments ne sont plus fusionnés automatiquement parce que deux appels utilisent le même outil. Gemini doit produire l'ensemble complet des arguments de l'action courante en s'appuyant sur le contexte. Java vérifie ces arguments sans compléter silencieusement avec des critères obsolètes.

## Tests d'acceptation

- Après « Présente toutes les directions » puis « Parle-moi de la première », la direction sélectionnée est mémorisée avec son identifiant réel.
- Après « Combien d'employés compte-t-elle ? », Gemini identifie cette direction et demande l'effectif de cette structure, pas celui du MEF entier.
- « Combien d'employés compte le MEF ? » est une nouvelle intention globale et ne réutilise pas la direction précédente.
- « Et ses services ? » conserve la direction si le référent est non ambigu.
- Si plusieurs entités peuvent correspondre à « elle », Bandy demande une précision et n'appelle aucun outil.
- Un changement explicite de structure remplace l'ancienne référence.
- Une nouvelle recherche remplace les critères incompatibles ; aucun filtre obsolète ne subsiste.
- Les permissions et validations du backend restent obligatoires, même si Gemini fournit un identifiant d'entité.
- Les réponses directes et les clarifications n'appellent aucun outil métier.

## Séquence de réalisation

1. Introduire le contrat et ses validations unitaires.
2. Ajouter la fonction interne Gemini et son schéma dynamique basé sur les outils autorisés.
3. Remplacer le routage de ConversationService et transmettre le contexte complet.
4. Persister les références d'entités dans l'état structuré, après audit du cycle de reconstruction mémoire.
5. Désactiver la fusion implicite dans ConversationStateMerger.
6. Supprimer les classificateurs lexicaux devenus inutiles et déplacer les raccourcis déterministes légitimes (salutations/commande explicite de nouvelle conversation) hors du résolveur d'intention.
7. Ajouter les tests d'orchestration et de bout en bout, puis exécuter la suite Maven complète.

## Hors périmètre de cette refonte

- Modifier les API métier de SYGPERS.
- Modifier les règles RH.
- Fusionner la branche ou la PR d'intégration de l'organisation.
- Ajouter du RAG avant que la compréhension conversationnelle et le suivi d'entités soient fiables.