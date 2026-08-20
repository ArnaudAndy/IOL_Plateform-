# Règles UML des diagrammes IOL

Le fichier `IOL_Diagrammes_v2.drawio` décrit le système au niveau de conception. Il ne constitue pas une vue du code source, des contrôleurs HTTP, des dépôts, des DTO ou des classes de framework.

## Diagrammes de classes

Une classe représente un concept du domaine, une responsabilité stable ou une interface de service. Les classes, attributs, opérations et énumérations utilisent l'anglais, qui est le vocabulaire de référence du modèle ; les noms d'associations sont en français pour expliciter la lecture métier. Les relations sont portées par les classificateurs, jamais par les compartiments d'attributs ou d'opérations.

Les attributs suivent strictement la forme UML portable :

`visibilité nom : Type [multiplicité] {contrainte}`

Exemples :

- `- id : String`
- `- startedAt : String {ISO-8601}`
- `- artifactUris : String [0..*] {URI}`

Les types de base retenus sont `String`, `Integer`, `Boolean` et `Real`. Un type métier est admis lorsqu'il est représenté dans le même diagramme, par exemple `ExecutionStatus` ou `WorkflowDirection`. Les formats et règles de validation sont des contraintes UML, placées entre accolades, et non des pseudo-types techniques.

Sont exclus des diagrammes de conception : les types Java (`long`, `byte[]`, `Map`), les annotations, les entités de persistance, les noms de topics, les routes HTTP et les classes d'implémentation. Ces éléments ont leur place dans le code, les diagrammes de composants ou la documentation d'exploitation.

Chaque classe est construite comme un classificateur UML avec ses compartiments enfants. Dans Draw.io, déplacer la classe déplace donc son nom, ses attributs et ses opérations ensemble.

Une vue de classes ne contient pas de cadre de regroupement décoratif. Un `package` UML ne doit être introduit que lorsqu'il exprime réellement un espace de nommage ou une organisation stable du modèle. Il est alors dessiné avec la notation de paquet UML et documenté comme tel ; il ne sert jamais à répartir artificiellement les classes en deux colonnes.

Les associations sont binaires sauf besoin réel d'une association n-aire. Leur nom est en français, lisible comme un verbe ou un groupe verbal. Les classes, attributs, opérations et énumérations restent en anglais. Les multiplicités sont présentes lorsque la relation exprime une contrainte de cardinalité métier.

Lorsqu'une énumération est utilisée comme type d'attribut, une dépendance UML en pointillé nommée `utilise` la relie au classificateur consommateur. Cette dépendance rend le type visible sans lui attribuer à tort une cardinalité d'association métier.

## Cas d'utilisation

Un acteur est toujours externe au système. Kafka, RustFS, Hop, Spark, les ordonnanceurs et les moteurs internes ne sont jamais des acteurs. Les diagrammes de cas d'utilisation expriment ce qu'une personne ou une organisation peut demander à IOL, sans exposer les décisions automatiques d'infrastructure.

Les diagrammes sont monochromes. Chaque page décrit un périmètre cohérent ; la vue globale reste volontairement synthétique et renvoie aux vues spécialisées pour le détail.

## Vérification

Après modification du générateur, exécuter :

```powershell
node scripts/generate_iol_drawio.mjs
.\scripts\validate_iol_drawio.ps1
```

Le validateur contrôle les pages attendues, les références Draw.io, l'ancrage des relations, l'absence de détails d'implémentation dans les diagrammes de classes et la syntaxe des attributs et opérations UML portables.

Le générateur ne régénère que `Classes_v2` et les pages `CD_*` non marquées comme maintenues manuellement lorsqu'un fichier Draw.io existe déjà. Les autres pages sont relues puis conservées telles quelles : une correction des classes ne doit jamais réécrire un cas d'utilisation, un diagramme de séquence, d'architecture ou de déploiement. Les pages `CD_Securite`, `CD_Configuration` et `CD_Interoperabilite` sont actuellement protégées de cette manière ; leur liste ne doit être modifiée qu'après accord explicite.

## Références

- [Spécification UML 2.5.1 de l'OMG](https://www.omg.org/spec/UML) : langage UML et types primitifs normatifs.
- [Organisation d'un modèle UML avec des packages, OMG](https://www.omg.org/certification/uml/documents/UML_Model_Organization_with_Packages.pdf) : les packages servent à organiser le modèle ; leur organisation dépend du besoin de modélisation.
