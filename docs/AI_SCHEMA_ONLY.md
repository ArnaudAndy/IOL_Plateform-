# Assistant IA SQL sans donnees metier

## Principe

L'assistant est specialise dans la generation de requetes SQL. Le chat
generaliste est desactive. La plateforme transmet uniquement :

- les noms de colonnes nettoyes ;
- les noms logiques des tables sources et cible ;
- l'intention fonctionnelle saisie par l'utilisateur ;
- le type de generation ;
- le dialecte de la base de destination.

Elle ne transmet aucune ligne, valeur d'exemple, statistique, URL JDBC, adresse,
utilisateur ou mot de passe. Le SQL retourne doit etre un unique
`SELECT` ou `WITH ... SELECT` et passe par le validateur de lecture seule.

## Dialecte de destination

Le backend determine le dialecte dans cet ordre :

1. destination du workflow ;
2. connexion de destination explicitement fournie ;
3. type de base explicitement fourni ;
4. destination par defaut.

Les dialectes pris en charge sont PostgreSQL, MySQL/MariaDB, SQL Server,
Oracle, SQLite, Snowflake et Redshift. Le fournisseur IA ne recoit que le nom du
dialecte, jamais la configuration de connexion.

## Gemini et Groq

Les deux fournisseurs utilisent une API compatible OpenAI. Les appels sont
repartis en round-robin. Si le fournisseur choisi echoue, le second est essaye
pour la meme requete.

Cette strategie augmente la disponibilite, mais ne supprime pas les quotas
contractuels. Les limites de chaque compte et les erreurs de politique restent
applicables.

Configuration locale dans `backend/.env` :

```dotenv
GEMINI_API_KEY=votre-cle
GEMINI_MODEL=gemini-3.6-flash
GROQ_API_KEY=votre-cle
GROQ_MODEL=llama-3.3-70b-versatile
AI_TIMEOUT_SECONDS=30
```

En production, injecter ces valeurs depuis un gestionnaire de secrets. Ne
jamais les placer dans `frontend/.env`, une variable `VITE_*`, un workflow
ou un journal. Une variable Vite est lisible par le navigateur.

## Routes

- `POST /api/ai/generate-schema-sql` : route canonique.
- `GET /api/ai/status` : configuration, modeles et strategie, sans cle.
- `POST /api/ai/chat` : refuse volontairement le chat generaliste.

Le frontend envoie aussi l'identifiant du workflow. Le backend charge lui-meme
la destination afin d'imposer le dialecte correct et ne fait pas confiance a un
dialecte choisi arbitrairement par le navigateur.
