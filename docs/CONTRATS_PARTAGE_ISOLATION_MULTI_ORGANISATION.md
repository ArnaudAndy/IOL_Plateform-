# Contrats de partage et isolation multi-organisation

Statut : architecture cible, non activee en production.

## 1. Verite sur l'etat actuel

La plateforme applique actuellement une propriete par utilisateur avec
`createdBy`. Cette protection ne constitue pas une isolation multi-organisation :

- un role ADMIN peut consulter plusieurs proprietaires ;
- les documents MongoDB ne portent pas tous un `organizationId` ;
- PostgreSQL, Kafka et RustFS utilisent encore des espaces partages ;
- les transactions OpenHIM ne sont pas encore rattachees a un contrat IOL actif.

IOL doit donc rester declare en mode **SINGLE_ORGANIZATION** tant que toutes les
barrieres ci-dessous ne sont pas appliquees. Aucun simple en-tete HTTP
`X-Organization-Id` ne doit etre considere comme fiable.

## 2. Contrat de partage

Le schema canonique est :

```text
contracts/data-sharing-contract.schema.json
```

Un contrat identifie sans ambiguite :

- l'organisation qui fournit et celle qui consomme ;
- la norme, sa version et l'adaptateur autorise ;
- la finalite et la base legale ;
- la liste exacte des champs autorises ou interdits ;
- la classification et la retention ;
- le canal OpenHIM et le profil d'authentification ;
- l'idempotence, les tentatives et la periode de validite ;
- les approbations des deux parties.

Un contrat `DRAFT`, `SUSPENDED`, `REVOKED` ou expire ne permet aucune livraison.
Une nouvelle liste de champs ou une nouvelle norme impose une nouvelle version.

## 3. Identite d'organisation

L'identite cible doit venir du jeton signe par IOL :

```text
JWT.sub              = utilisateur
JWT.organization_id  = organisation active
JWT.membership_id    = appartenance verifiee
JWT.roles            = roles dans cette organisation
```

Le backend derive le contexte d'organisation du JWT. Il refuse toute valeur
d'organisation fournie uniquement par le navigateur, le corps JSON ou une URL.
Les appels internes utilisent une identite de service mTLS avec une organisation
ou un role de plateforme explicitement autorise.

## 4. Barrieres obligatoires

| Couche | Isolation requise |
|---|---|
| API | Filtre obligatoire `organizationId` sur lecture, modification et suppression |
| MongoDB | `organizationId` non nul et index compose `{organizationId, id}` |
| PostgreSQL | Schema ou base par organisation, role SQL distinct, aucun role global applicatif |
| Kafka | `organizationId` signe dans l'enveloppe, ACL de topic, controle producteur et consommateur |
| RustFS | Bucket ou prefixe par organisation avec politique S3, jamais une cle libre |
| OpenHIM | Client, certificat, canal et contrat rattaches a l'organisation |
| Audit | Organisation, contrat, correlation et decision d'autorisation enregistres |
| Cache | Toute cle commence par l'organisation ; aucun cache partage sans partition |
| Sauvegarde | Restauration testee sans rendre les donnees visibles a une autre organisation |

## 5. Enveloppe Kafka cible

Le schema `contracts/pipeline-event-envelope.schema.json` rend obligatoires
`organizationId`, `correlationId` et `workflowId`. Pour INBOUND et OUTBOUND,
`contractId` devient egalement obligatoire.

Les donnees normales restent dans Kafka. Les volumes Big Data utilisent
`dataReference` vers RustFS avec SHA-256. Une enveloppe ne peut pas contenir a la
fois une charge complete et une reference Big Data.

## 6. Points de controle d'un echange

```text
Systeme A
  -> certificat OpenHIM rattache a organisation A
  -> canal rattache au contrat actif
  -> mediateur valide norme + champs autorises
  -> api-core fixe organizationId depuis l'identite de service
  -> Kafka transporte l'enveloppe signee
  -> consumer verifie organisation et contrat
  -> stockage isole de A
  -> workflow OUTBOUND verifie le contrat A -> B
  -> OpenHIM livre uniquement par le canal autorise a B
```

Le controle est repete a chaque frontiere. La confiance accordee par OpenHIM ne
remplace pas l'isolation des stockages internes.

## 7. Strategie d'activation

1. Creer les organisations et les appartenances, puis ajouter les claims JWT.
2. Ajouter `organizationId` a chaque ressource et migrer l'existant vers
   l'organisation par defaut.
3. Remplacer toutes les recherches globales par des recherches tenant-aware.
4. Partitionner PostgreSQL, Kafka et RustFS, puis appliquer leurs ACL.
5. Rendre le contrat actif obligatoire pour INBOUND et OUTBOUND.
6. Executer les tests de fuite croisee, de restauration et de revocation.
7. Activer MULTI_ORGANIZATION uniquement apres validation de securite.

## 8. Tests de sortie obligatoires

- un utilisateur de A ne peut deviner, lire, modifier ni executer une ressource de B ;
- un ADMIN de A ne devient jamais ADMIN de plateforme ;
- une cle RustFS de B est refusee au consumer de A ;
- un message Kafka avec organisation modifiee est rejete et place en DLQ ;
- la revocation du contrat bloque la nouvelle livraison sans supprimer l'audit ;
- une restauration de A ne cree aucun document ou schema accessible a B ;
- les logs ne contiennent ni charge sensible ni secret.

Tant que ces tests ne passent pas, la plateforme reste mono-organisation, meme
si l'interface affiche plusieurs partenaires.
