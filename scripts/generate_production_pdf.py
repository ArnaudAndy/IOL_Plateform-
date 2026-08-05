from pathlib import Path
import textwrap

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "GUIDE_MISE_EN_PRODUCTION.pdf"
PAGE_W, PAGE_H = 8.27, 11.69

COLORS = {
    "ink": "#16212a",
    "muted": "#52616f",
    "line": "#8298a8",
    "blue": "#dcefff",
    "green": "#dff4e5",
    "amber": "#fff0c2",
    "red": "#ffe2df",
    "gray": "#edf2f5",
    "purple": "#eadfff",
    "teal": "#dff7f4",
}


def new_page(title, subtitle=None):
    fig, ax = plt.subplots(figsize=(PAGE_W, PAGE_H))
    ax.set_xlim(0, 100)
    ax.set_ylim(0, 140)
    ax.axis("off")
    ax.text(6, 134, title, fontsize=18, weight="bold", color=COLORS["ink"], va="top")
    if subtitle:
        ax.text(6, 128, subtitle, fontsize=9, color=COLORS["muted"], va="top")
        y = 121
    else:
        y = 124
    return fig, ax, y


def finish(pdf, fig):
    pdf.savefig(fig, bbox_inches="tight")
    plt.close(fig)


def lines(text, width):
    return textwrap.wrap(text, width=width, replace_whitespace=False) or [""]


def paragraph(ax, text, x, y, width=92, size=9.2, leading=3.7, weight="normal"):
    for block in text.split("\n"):
        for line in lines(block, width):
            ax.text(x, y, line, fontsize=size, color=COLORS["ink"],
                    va="top", weight=weight)
            y -= leading
        y -= leading * 0.3
    return y


def heading(ax, text, y):
    ax.text(6, y, text, fontsize=11, weight="bold", color=COLORS["ink"], va="top")
    return y - 5


def bullets(ax, items, y, width=86, size=8.8):
    for item in items:
        wrapped = lines(item, width)
        ax.text(8, y, "-", fontsize=size + 1, color=COLORS["ink"], va="top")
        for index, line in enumerate(wrapped):
            ax.text(11, y, line, fontsize=size, color=COLORS["ink"], va="top")
            y -= 3.4
        y -= 0.8
    return y


def box(ax, x, y, w, h, title, body="", color="gray", size=8.5):
    patch = FancyBboxPatch(
        (x, y), w, h,
        boxstyle="round,pad=0.4,rounding_size=1",
        linewidth=1,
        edgecolor=COLORS["line"],
        facecolor=COLORS[color],
    )
    ax.add_patch(patch)
    ax.text(x + w / 2, y + h - 2.5, title, fontsize=size, weight="bold",
            color=COLORS["ink"], ha="center", va="top")
    body_y = y + h - 6
    for line in lines(body, max(12, int(w * 1.2))):
        ax.text(x + w / 2, body_y, line, fontsize=size - 1,
                color=COLORS["muted"], ha="center", va="top")
        body_y -= 2.8


def arrow(ax, x1, y1, x2, y2, label=None):
    patch = FancyArrowPatch(
        (x1, y1), (x2, y2),
        arrowstyle="-|>",
        mutation_scale=11,
        linewidth=1.2,
        color=COLORS["line"],
        shrinkA=2,
        shrinkB=2,
    )
    ax.add_patch(patch)
    if label:
        ax.text((x1 + x2) / 2, (y1 + y2) / 2 + 1.8, label,
                fontsize=7.3, color=COLORS["muted"], ha="center")


def title_page(pdf):
    fig, ax, _ = new_page("IOL - Guide de mise en production",
                          "Architecture, securite des fichiers, reprise, CI et plan de bascule")
    box(ax, 7, 91, 21, 15, "Charge normale", "Donnees dans Kafka", "green", 9)
    box(ax, 39, 91, 21, 15, "Big Data", "Donnees dans RustFS", "purple", 9)
    box(ax, 71, 91, 21, 15, "Calcul", "LOCAL ou Spark automatique", "blue", 9)
    arrow(ax, 28, 98.5, 39, 98.5)
    arrow(ax, 60, 98.5, 71, 98.5)
    y = 80
    y = paragraph(
        ax,
        "Objectif : passer d'une topologie de developpement fonctionnelle a une "
        "plateforme exploitable, securisee, observable et testee en charge.",
        9, y, 80, 11, 4.8, "bold",
    )
    y = heading(ax, "Decisions deja implementees", y - 4)
    bullets(ax, [
        "Basculement automatique a 10 millions de lignes ou 2 Gio.",
        "Aucun CSV JDBC : JSON type transporte par Kafka ou RustFS.",
        "Quarantaine ClamAV en mode ferme avec purge controlee a 30 jours.",
        "Sauvegarde validee par restauration isolee puis copie Restic hors site.",
        "Suppression RustFS apres succes et ACK Kafka ; retention d'echec de 72 h.",
        "Gemini et Groq en round-robin avec failover et contexte schema-only.",
        "Dialecte SQL resolu depuis la base de destination du workflow.",
    ], y, 79, 9.2)
    ax.text(6, 8, "Version du 30 juillet 2026", fontsize=8, color=COLORS["muted"])
    finish(pdf, fig)


def runtime_page(pdf):
    fig, ax, y = new_page("1. Decision automatique et transport",
                          "L'utilisateur choisit une intention, la plateforme choisit le moteur")
    box(ax, 6, 100, 16, 13, "Source", "JDBC / API / fichier", "gray")
    box(ax, 30, 100, 18, 13, "api-core", "seul acces source", "blue")
    box(ax, 57, 112, 18, 12, "Kafka", "donnees normales", "green")
    box(ax, 57, 88, 18, 12, "RustFS", "donnees Big Data", "purple")
    box(ax, 83, 100, 13, 13, "Consumer", "controle + moteur", "teal")
    arrow(ax, 22, 106.5, 30, 106.5)
    arrow(ax, 48, 108, 57, 118, "< 10 M")
    arrow(ax, 48, 104, 57, 94, ">= 10 M")
    arrow(ax, 75, 118, 83, 108)
    arrow(ax, 75, 94, 83, 104, "manifeste")
    y = 77
    y = heading(ax, "Seuils initiaux de production", y)
    y = bullets(ax, [
        "JDBC : 10 000 000 lignes.",
        "Fichier : 2 Gio.",
        "Volume inconnu ou diagnostic expire : chemin Spark par prudence.",
        "Partie multipart RustFS : 64 Mio.",
        "Upload authentifie : 5 Gio, sans buffering Nginx.",
        "Evenement Kafka : 8 Mio maximum, limite de securite et non seuil Big Data.",
    ], y)
    y = heading(ax, "Dimensionnement", y - 2)
    paragraph(
        ax,
        "Valider les seuils par tests de charge. Une partie de 64 Mio couvre "
        "environ 625 Gio avec 10 000 parties. Au-dela, augmenter la partie a "
        "256/512 Mio ou decouper en plusieurs objets.",
        6, y, 90, 9.2,
    )
    finish(pdf, fig)


def rustfs_page(pdf):
    fig, ax, y = new_page("2. Cycle de vie RustFS",
                          "L'objet est temporaire, mais jamais supprime avant la fin sure du flow")
    box(ax, 6, 106, 16, 13, "Upload", "multipart + SHA-256", "purple")
    box(ax, 29, 106, 16, 13, "Controle", "taille + hash", "blue")
    box(ax, 52, 106, 16, 13, "Execution", "Spark + destination", "teal")
    box(ax, 75, 106, 19, 13, "Succes", "statut + ACK Kafka", "green")
    arrow(ax, 22, 112.5, 29, 112.5)
    arrow(ax, 45, 112.5, 52, 112.5)
    arrow(ax, 68, 112.5, 75, 112.5)
    arrow(ax, 84.5, 106, 84.5, 91, "supprimer")
    box(ax, 72, 76, 25, 13, "Suppression RustFS", "apres ACK uniquement", "green")
    box(ax, 37, 76, 25, 13, "Echec", "erreur expliquee / DLQ", "red")
    arrow(ax, 60, 106, 49.5, 89, "si echec")
    box(ax, 4, 76, 25, 13, "Retention", "72 h puis purge horaire", "amber")
    arrow(ax, 37, 82.5, 29, 82.5)
    y = 65
    y = heading(ax, "Ordre de securite", y)
    y = bullets(ax, [
        "Le consumer telecharge dans un chemin controle et verifie le SHA-256.",
        "Le fichier local est supprime dans le bloc de finalisation.",
        "Apres succes : statut terminal, ACK Kafka, puis suppression RustFS.",
        "Apres echec : conservation pour diagnostic et rejeu, maximum 72 heures.",
        "Bronze, Silver et Gold suivent la retention metier de la destination.",
    ], y)
    finish(pdf, fig)


def clamav_page(pdf):
    fig, ax, y = new_page("3. Quarantaine et ClamAV",
                          "Aucun fichier n'est utilisable avant un verdict acceptable")
    box(ax, 4, 105, 18, 14, "Reception", "multipart controle", "gray")
    box(ax, 29, 105, 18, 14, "Quarantaine", "volume separe + SHA-256", "amber")
    box(ax, 55, 105, 18, 14, "ClamAV", "volume en lecture seule", "blue")
    box(ax, 80, 116, 16, 11, "CLEAN", "promouvoir", "green")
    box(ax, 80, 94, 16, 11, "ERREUR", "conserver", "red")
    arrow(ax, 22, 112, 29, 112)
    arrow(ax, 47, 112, 55, 112)
    arrow(ax, 73, 114, 80, 121)
    arrow(ax, 73, 108, 80, 99)
    y = 80
    y = heading(ax, "Comportement de production", y)
    y = bullets(ax, [
        "fail-closed=true : scanner indisponible ou reponse incomplete = refus.",
        "Le workflow ne peut resoudre que les fichiers du volume approuve.",
        "La readiness API devient DOWN si ClamAV ne repond plus.",
        "Les dossiers UUID infectes ou en erreur sont purges apres 30 jours.",
        "La quarantaine est sauvegardee pour permettre l'analyse d'incident.",
    ], y)
    y = heading(ax, "Limite explicite", y - 2)
    paragraph(
        ax,
        "ClamAV est limite ici a 2 Gio par fichier. Un upload direct plus grand "
        "reste bloque. Les charges JDBC/API massives utilisent RustFS ; les gros "
        "fichiers directs doivent etre decoupes ou passer par une future passerelle "
        "d'ingestion massive. Ne jamais desactiver le scan pour les accepter.",
        6, y, 90, 9.1, weight="bold",
    )
    finish(pdf, fig)


def backup_page(pdf):
    fig, ax, y = new_page("4. Sauvegarde prouvee par restauration",
                          "Le job reste en echec tant que la reprise n'est pas demontree")
    box(ax, 4, 106, 17, 14, "Sources", "SQL, Mongo, fichiers, RustFS", "gray")
    box(ax, 27, 106, 17, 14, "Backup", "dump + archive + miroir", "blue")
    box(ax, 50, 106, 17, 14, "Empreintes", "SHA256SUMS", "amber")
    box(ax, 73, 106, 22, 14, "Restauration", "conteneurs isoles", "green")
    arrow(ax, 21, 113, 27, 113)
    arrow(ax, 44, 113, 50, 113)
    arrow(ax, 67, 113, 73, 113)
    box(ax, 38, 80, 24, 13, "Restic hors site", "chiffrement + check 5 %", "purple")
    arrow(ax, 84, 106, 62, 91)
    y = 68
    y = heading(ax, "Cycle nocturne", y)
    y = bullets(ax, [
        "PostgreSQL est restaure avec pg_restore --exit-on-error.",
        "MongoDB IOL et OpenHIM sont restaures dans des instances temporaires.",
        "Les archives uploads et quarantaine sont relues.",
        "Le contenu RustFS est restaure dans un serveur objet isole.",
        "Restic envoie la copie chiffree hors site et controle un sous-ensemble.",
        "Le timer systemd est persistant et reprend un cycle manque.",
    ], y)
    paragraph(
        ax,
        "Le script et sa syntaxe sont valides. Le proces-verbal de production doit "
        "encore contenir la preuve d'un cycle complet execute avec le stack actif.",
        6, y - 2, 90, 9.2, weight="bold",
    )
    finish(pdf, fig)


def ai_page(pdf):
    fig, ax, y = new_page("5. IA SQL sans donnees",
                          "Deux fournisseurs, un contexte strict et un dialecte de destination")
    box(ax, 5, 103, 20, 15, "Entree", "noms de colonnes + intention", "gray")
    box(ax, 34, 103, 21, 15, "api-core", "dialecte + schema-only", "blue")
    box(ax, 66, 115, 18, 12, "Gemini", "round-robin", "purple")
    box(ax, 66, 91, 18, 12, "Groq", "failover", "teal")
    box(ax, 88, 103, 9, 15, "SQL", "SELECT valide", "green", 7.8)
    arrow(ax, 25, 110.5, 34, 110.5)
    arrow(ax, 55, 112, 66, 121)
    arrow(ax, 55, 108, 66, 97)
    arrow(ax, 84, 121, 88, 112)
    arrow(ax, 84, 97, 88, 108)
    y = 79
    y = heading(ax, "Informations autorisees", y)
    y = bullets(ax, [
        "Noms de colonnes et noms logiques de tables.",
        "Instruction fonctionnelle et type de generation.",
        "Nom du dialecte : PostgreSQL, MySQL, SQL Server, Oracle, SQLite, Snowflake ou Redshift.",
    ], y)
    y = heading(ax, "Informations interdites", y - 2)
    y = bullets(ax, [
        "Lignes, valeurs d'exemple, statistiques et donnees personnelles.",
        "Hote, URL JDBC, utilisateur, mot de passe ou cle technique.",
        "Chat generaliste : la route refuse toute demande hors generation SQL.",
    ], y)
    paragraph(
        ax,
        "La rotation et le failover augmentent la disponibilite ; ils ne rendent "
        "pas les quotas illimites. Les cles sont injectees depuis un gestionnaire "
        "de secrets et ne sont jamais exposees au navigateur.",
        6, y - 2, 90, 9.2, weight="bold",
    )
    finish(pdf, fig)


def reliability_page(pdf):
    fig, ax, y = new_page("6. Fiabilite des livraisons OUTBOUND",
                          "Reprise multi-instance, poison pill et protection SSRF")
    box(ax, 5, 103, 24, 16, "Ledger MongoDB",
        "claim atomique + lease + statut terminal", "green")
    box(ax, 38, 103, 24, 16, "Poison pill",
        "JSON invalide vers DLQ puis progression", "amber")
    box(ax, 71, 103, 24, 16, "Garde SSRF",
        "HTTP(S), DNS, reseaux prives et allow-list", "blue")
    y = 82
    y = heading(ax, "Garanties implementees", y)
    y = bullets(ax, [
        "La cle d'idempotence survit au redemarrage, au rebalance Kafka et aux replicas.",
        "Un worker ne peut reprendre un message actif qu'apres expiration de son lease.",
        "Un JSON corrompu est publie en DLQ et ne bloque pas la partition Kafka.",
        "Les credentials dans l'URL, localhost et les adresses privees sont refuses.",
        "La resolution DNS est controlee pour limiter les contournements par rebinding.",
    ], y)
    y = heading(ax, "Garantie de bout en bout", y - 2)
    paragraph(
        ax,
        "Le worker transmet aussi Idempotency-Key au systeme cible. La cible doit "
        "persister cette cle : c'est la protection finale contre une coupure apres "
        "le POST mais avant la confirmation du ledger IOL.",
        6, y, 90, 9.4, weight="bold",
    )
    finish(pdf, fig)


def delivery_page(pdf):
    fig, ax, y = new_page("7. Portes CI et probes runtime",
                          "Refuser un changement dangereux et retirer un composant indisponible")
    box(ax, 5, 108, 25, 16, "Tests", "Java, Node, Python, frontend", "green")
    box(ax, 38, 108, 25, 16, "Securite", "Gitleaks + Trivy + dependances", "red")
    box(ax, 71, 108, 25, 16, "Release", "approbation + SBOM + signature", "purple")
    arrow(ax, 30, 116, 38, 116)
    arrow(ax, 63, 116, 71, 116)
    box(ax, 5, 78, 25, 15, "api-core", "SQL, Mongo, Kafka, AV, RustFS", "blue")
    box(ax, 38, 78, 25, 15, "Consumer", "Kafka, verrou SQL, RustFS", "teal")
    box(ax, 71, 78, 25, 15, "Mediateur", "OpenHIM + worker Kafka", "amber")
    y = 64
    y = heading(ax, "Regles", y)
    y = bullets(ax, [
        "Liveness controle seulement le processus.",
        "Readiness controle les dependances necessaires au nouveau travail.",
        "Une readiness DOWN retire l'instance du trafic sans boucle de redemarrage.",
        "La porte de qualite refuse toute verification en echec ou annulee.",
        "La branche main et l'environnement production-release exigent une approbation.",
    ], y)
    finish(pdf, fig)


def templates_tenancy_page(pdf):
    fig, ax, y = new_page("8. Modeles et partage multi-organisation",
                          "Simplifier l'usage sans promettre une isolation qui n'existe pas encore")
    box(ax, 5, 105, 26, 17, "Catalogue", "JDBC, fichier, inbound, multi-source", "green")
    box(ax, 38, 105, 26, 17, "Copie sure", "sans secret, ID, owner ou destination", "blue")
    box(ax, 71, 105, 25, 17, "Execution", "moteur choisi automatiquement", "teal")
    arrow(ax, 31, 113.5, 38, 113.5)
    arrow(ax, 64, 113.5, 71, 113.5)
    y = 82
    y = heading(ax, "Contrats de partage", y)
    y = bullets(ax, [
        "Schema JSON pour organisations, finalite, champs, retention et interdictions.",
        "Enveloppe Kafka cible avec organizationId, contractId et correlationId.",
        "Exemple versionne hopital A vers hopital B valide en CI.",
    ], y)
    y = heading(ax, "Garde-fou", y - 2)
    paragraph(
        ax,
        "Le contrat ne suffit pas a rendre IOL multi-tenant. Le runtime reste "
        "SINGLE_ORGANIZATION jusqu'a l'isolation JWT, SQL, Mongo, Kafka, RustFS, "
        "logs, sauvegardes et cles, accompagnee de tests negatifs. Les packs "
        "FHIR/DHIS2/SORMAS restent une phase ulterieure.",
        6, y, 90, 9.3, weight="bold",
    )
    finish(pdf, fig)


def target_page(pdf):
    fig, ax, y = new_page("9. Topologie cible de production",
                          "Les composants du compose local doivent devenir redondes et securises")
    box(ax, 39, 120, 22, 10, "Load balancer TLS", "", "blue")
    box(ax, 16, 101, 20, 11, "api-core 1", "stateless", "green")
    box(ax, 64, 101, 20, 11, "api-core 2", "stateless", "green")
    box(ax, 5, 77, 22, 12, "Kafka", "3+ brokers, RF3", "amber")
    box(ax, 39, 77, 22, 12, "MongoDB", "replica set + TLS", "purple")
    box(ax, 73, 77, 22, 12, "RustFS", "HA + TLS", "teal")
    box(ax, 7, 52, 18, 11, "Consumer 1", "", "gray")
    box(ax, 30, 52, 18, 11, "Consumer 2", "", "gray")
    box(ax, 53, 52, 18, 11, "Consumer 3", "", "gray")
    box(ax, 77, 52, 18, 11, "Spark", "workers multiples", "blue")
    arrow(ax, 50, 120, 26, 112)
    arrow(ax, 50, 120, 74, 112)
    arrow(ax, 26, 101, 16, 89)
    arrow(ax, 74, 101, 84, 89)
    arrow(ax, 36, 101, 45, 89)
    arrow(ax, 64, 101, 55, 89)
    arrow(ax, 16, 77, 16, 63)
    arrow(ax, 16, 77, 39, 63)
    arrow(ax, 16, 77, 62, 63)
    arrow(ax, 71, 57.5, 77, 57.5)
    y = 40
    y = heading(ax, "Bloqueurs actuels", y)
    bullets(ax, [
        "Chiffrer les mots de passe source et destination avec KMS/Vault.",
        "Remplacer Kafka mono-broker PLAINTEXT et Mongo sans authentification.",
        "Configurer TLS, secrets uniques, sauvegardes et restauration testee.",
        "Epingler et scanner les images ; gerer les schemas avec Flyway/Liquibase.",
    ], y, 84, 8.5)
    finish(pdf, fig)


def checklist_page(pdf):
    fig, ax, y = new_page("10. Controle avant ouverture",
                          "La bascule est une preuve, pas seulement un changement de variables")
    y = heading(ax, "Tests obligatoires", y)
    y = bullets(ax, [
        "Tests API, consumer et build frontend sans erreur.",
        "Fichier sain, EICAR, panne ClamAV et retour automatique de readiness.",
        "Cycle backup, restauration isolee et recuperation Restic sur un autre hote.",
        "9,9 M puis 10,1 M de lignes ; fichier sous puis au-dessus de 2 Gio.",
        "Arret consumer, rejeu Kafka, idempotence et poison pill vers DLQ.",
        "Echec Spark : objet conserve ; succes : objet supprime apres ACK.",
        "Indisponibilite Gemini puis Groq, et failover dans les deux sens.",
        "Restauration MongoDB/PostgreSQL et repetition du retour arriere.",
        "Test SSRF, RBAC, secrets, TLS et journaux expurges.",
    ], y)
    y = heading(ax, "Autorisation de mise en production", y - 2)
    y = bullets(ax, [
        "Aucun secret dans Git, les images, le frontend ou les logs.",
        "Tous les bloqueurs de securite et de haute disponibilite sont fermes.",
        "SLA de charge et de reprise atteints avec les volumes reels.",
        "Dashboards, alertes, astreinte et procedures d'incident operationnels.",
        "Validation securite du traitement IA schema-only.",
    ], y)
    box(
        ax, 8, 16, 84, 16,
        "Verdict",
        "Le code fournit les mecanismes de basculement, de retention et de failover. "
        "Le passage en production exige encore le durcissement de l'infrastructure et "
        "le chiffrement des credentials metier.",
        "amber", 9,
    )
    finish(pdf, fig)


def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with PdfPages(OUT) as pdf:
        title_page(pdf)
        runtime_page(pdf)
        rustfs_page(pdf)
        clamav_page(pdf)
        backup_page(pdf)
        ai_page(pdf)
        reliability_page(pdf)
        delivery_page(pdf)
        templates_tenancy_page(pdf)
        target_page(pdf)
        checklist_page(pdf)
    print(OUT)


if __name__ == "__main__":
    main()
