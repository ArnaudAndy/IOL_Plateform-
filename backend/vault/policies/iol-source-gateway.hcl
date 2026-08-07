# Politique du source-gateway.
#
# Le gateway DECHIFFRE les identifiants des connexions source pour ouvrir la
# base d'un client. Il ne doit jamais pouvoir en CHIFFRER de nouveaux: la
# creation et la rotation d'une connexion restent des operations d'api-core,
# declenchees par un utilisateur authentifie.
#
# Cette asymetrie est le coeur du confinement: une compromission du gateway
# expose les sources qu'il lit deja, mais ne permet pas d'enregistrer une
# nouvelle connexion ni de reecrire un secret existant.

path "transit/decrypt/iol-business-credentials" {
  capabilities = ["update"]
}

# Lecture des metadonnees de la cle, necessaire pour verifier la version de
# chiffrement d'une enveloppe. N'expose pas la cle elle-meme.
path "transit/keys/iol-business-credentials" {
  capabilities = ["read"]
}

path "sys/health" {
  capabilities = ["read"]
}
