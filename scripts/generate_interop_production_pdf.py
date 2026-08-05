from pathlib import Path

from matplotlib.backends.backend_pdf import PdfPages

from generate_production_pdf import (
    COLORS,
    arrow,
    box,
    bullets,
    finish,
    heading,
    new_page,
    paragraph,
)


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "INTEROPERABILITE_FHIR_ISO20022_EDFI_PRODUCTION.pdf"


def title_page(pdf):
    fig, ax, _ = new_page(
        "IOL - Interoperabilite en production",
        "FHIR R4, ISO 20022, Ed-Fi et transport massif sans CSV",
    )
    box(ax, 6, 98, 17, 14, "OpenHIM", "Entree TLS et tracabilite", "blue", 9)
    box(ax, 30, 98, 17, 14, "Mediateurs", "Validation de norme", "teal", 9)
    box(ax, 54, 98, 17, 14, "Transport", "Kafka ou RustFS", "green", 9)
    box(ax, 78, 98, 17, 14, "Pipeline", "Bronze, Silver, Gold", "purple", 9)
    arrow(ax, 23, 105, 30, 105)
    arrow(ax, 47, 105, 54, 105)
    arrow(ax, 71, 105, 78, 105)
    y = 84
    y = paragraph(
        ax,
        "Objectif : recevoir des echanges standards, petits ou massifs, sans "
        "connexion directe du moteur a la source et sans conversion CSV.",
        9, y, 80, 11, 4.8, "bold",
    )
    y = heading(ax, "Decisions fermes", y - 5)
    bullets(ax, [
        "Kafka transporte toute la donnee en volume normal.",
        "RustFS est reserve au Big Data ou a un record trop grand pour Kafka.",
        "Le choix du transport et du moteur est automatique et invisible au metier.",
        "Le pivot INBOUND est en JSON Lines ; aucun CSV temporaire.",
        "RustFS est temporaire : suppression apres succes, retention d'echec 72 h.",
        "Le runtime reste mono-organisation tant que l'isolation n'est pas prouvee.",
    ], y, 80, 9.3)
    ax.text(6, 8, "Version technique 1.1.0 - 30 juillet 2026",
            fontsize=8, color=COLORS["muted"])
    finish(pdf, fig)


def architecture_page(pdf):
    fig, ax, y = new_page(
        "1. Chemin complet d'un message",
        "Les moteurs de traitement ne se reconnectent jamais au systeme externe",
    )
    box(ax, 4, 104, 15, 13, "Externe", "FHIR / ISO / Ed-Fi", "gray")
    box(ax, 24, 104, 15, 13, "OpenHIM", "Auth + transaction", "blue")
    box(ax, 44, 104, 16, 13, "Pack Java", "Parse + valide", "teal")
    box(ax, 65, 104, 14, 13, "Pivot IOL", "NDJSON progressif", "amber")
    box(ax, 84, 104, 12, 13, "api-core", "Decision", "green")
    for left, right in [(19, 24), (39, 44), (60, 65), (79, 84)]:
        arrow(ax, left, 110.5, right, 110.5)
    box(ax, 28, 75, 18, 13, "Kafka", "Lots de lignes ordonnes", "green")
    box(ax, 55, 75, 18, 13, "RustFS", "Objet JSONL multipart", "purple")
    box(ax, 82, 75, 14, 13, "Consumer", "Controle + execute", "teal")
    arrow(ax, 90, 104, 46, 88, "volume normal")
    arrow(ax, 92, 104, 64, 88, "Big Data")
    arrow(ax, 46, 81.5, 82, 81.5)
    arrow(ax, 73, 81.5, 82, 81.5)
    y = 65
    y = heading(ax, "Responsabilites", y)
    bullets(ax, [
        "OpenHIM authentifie le partenaire, choisit le canal et trace la transaction.",
        "Le pack de norme refuse un message dangereux ou structurellement invalide.",
        "Le pivot conserve la charge standard complete dans une enveloppe non destructive.",
        "api-core transfere d'abord toutes les donnees, puis publie la commande.",
        "Le consumer execute Hop ou Spark a partir du transport IOL uniquement.",
    ], y, 84)
    finish(pdf, fig)


def transport_page(pdf):
    fig, ax, y = new_page(
        "2. Entree massive et cycle de vie",
        "Seuils production : 10 M lignes ou 2 Gio",
    )
    box(ax, 5, 106, 18, 13, "NDJSON entrant", "Lecture ligne par ligne", "blue")
    box(ax, 30, 106, 18, 13, "Validation", "Lots de 500 lignes", "teal")
    box(ax, 57, 116, 18, 12, "Kafka", "< seuil et record sur", "green")
    box(ax, 57, 93, 18, 12, "RustFS", ">= seuil / record large", "purple")
    box(ax, 83, 106, 13, 13, "Commande", "apres transfert", "amber")
    arrow(ax, 23, 112.5, 30, 112.5)
    arrow(ax, 48, 114, 57, 122)
    arrow(ax, 48, 108, 57, 99)
    arrow(ax, 75, 122, 83, 114)
    arrow(ax, 75, 99, 83, 108)
    y = 82
    y = heading(ax, "Bornes et reprise", y)
    bullets(ax, [
        "Entree progressive : 10 Gio maximum ; une ligne : 128 Mio maximum.",
        "Transaction specialisee FHIR/ISO/Ed-Fi : 256 Mio par requete.",
        "Les lots Kafka portent transferId, index, taille et checksum.",
        "Un transfert partiel publie PIPELINE_SOURCE_TRANSFER_ABORTED et est nettoye.",
        "Un objet RustFS est supprime apres succes ; un echec est purge apres 72 h.",
    ], y, 83)
    y = heading(ax, "Pourquoi JSONL ?", y - 2)
    paragraph(
        ax,
        "JSONL garde les types et les structures JSON, se lit progressivement et "
        "evite les problemes de separateur, d'echappement et de typage du CSV.",
        8, y, 86, 9.2,
    )
    finish(pdf, fig)


def domain_page(pdf, number, title, subtitle, boxes, items, warning):
    fig, ax, y = new_page(f"{number}. {title}", subtitle)
    x = 5
    for index, (box_title, body, color) in enumerate(boxes):
        width = 18 if index != len(boxes) - 1 else 19
        box(ax, x, 105, width, 14, box_title, body, color)
        if index < len(boxes) - 1:
            arrow(ax, x + width, 112, x + width + 6, 112)
        x += width + 6
    y = 92
    y = heading(ax, "Deroulement reel", y)
    y = bullets(ax, items, y, 84, 9)
    box(ax, 7, 16, 86, 18, "Limite de conformite", warning, "amber", 8.7)
    finish(pdf, fig)


def fhir_page(pdf):
    domain_page(
        pdf, 3, "Sante - FHIR R4",
        "Un laboratoire transmet Patients, Observations et DiagnosticReports",
        [
            ("Bundle", "JSON ou XML", "gray"),
            ("HAPI FHIR", "Parse R4 strict", "blue"),
            ("Validation", "Core + Bundle", "teal"),
            ("Pivot", "1 record / ressource", "green"),
        ],
        [
            "Le laboratoire appelle POST /interop/fhir avec un client OpenHIM.",
            "Le mediateur valide la structure R4 et les entrees du Bundle.",
            "Chaque ressource est preservee integralement dans fhir_resource_json.",
            "Le workflow charge Bronze puis applique les regles locales en Silver/Gold.",
            "Le meme correlationId relie OpenHIM, l'execution IOL et la DLQ.",
        ],
        "Le socle R4 n'est pas un profil national. Charger l'Implementation Guide, "
        "les StructureDefinition, ValueSet et la terminologie avant certification.",
    )


def iso_page(pdf):
    domain_page(
        pdf, 4, "Finance - ISO 20022",
        "Une banque transmet pain.001, pacs.008 ou camt",
        [
            ("Message MX", "XML standard", "gray"),
            ("XML sur", "XXE et DTD bloques", "red"),
            ("Prowide", "Type + famille", "blue"),
            ("Pivot", "XML + JSON preserves", "green"),
        ],
        [
            "La banque appelle POST /interop/iso20022 via son canal prive.",
            "Le namespace et le Message Definition Identifier sont identifies.",
            "Une liste blanche peut limiter pain, pacs, camt ou une autre famille.",
            "Le XML original et la representation Prowide ne sont pas aplatis.",
            "Les controles de doublon et de montant sont appliques par le workflow.",
        ],
        "La validation du modele ne certifie pas CBPR+, SEPA ou une market practice. "
        "Signatures, anti-fraude et regles du reseau restent contractuelles.",
    )


def edfi_page(pdf):
    domain_page(
        pdf, 5, "Education - Ed-Fi",
        "Un systeme scolaire synchronise des ressources paginees",
        [
            ("Students", "JSON / NDJSON", "gray"),
            ("Ed-Fi pack", "IDs + refs + ETag", "blue"),
            ("Handoff", "Lots progressifs", "teal"),
            ("Pivot", "JSON complet preserve", "green"),
        ],
        [
            "Le SIS appelle POST /interop/edfi/students.",
            "Le nom de ressource vient du chemin ou de X-EdFi-Resource.",
            "Objet, UUID, _etag et champs *Reference sont controles.",
            "Les pages ordinaires passent dans Kafka ; un historique massif via RustFS.",
            "Le contenu integral reste disponible dans edfi_payload_json.",
        ],
        "La conformite exacte depend de la version et des extensions OpenAPI de "
        "l'ODS/API cible. Les schemas du partenaire doivent etre versionnes.",
    )


def operations_page(pdf):
    fig, ax, y = new_page(
        "6. Erreurs, isolation et exploitation",
        "Une erreur doit etre visible, explicable et recuperable",
    )
    box(ax, 5, 106, 19, 14, "Validation", "400 + issues precises", "red")
    box(ax, 29, 106, 19, 14, "Transport", "abort + nettoyage", "amber")
    box(ax, 53, 106, 19, 14, "Execution", "FAILED + DLQ", "purple")
    box(ax, 77, 106, 19, 14, "Suivi", "correlationId", "blue")
    y = 93
    y = heading(ax, "Mono-organisation volontaire", y)
    y = paragraph(
        ax,
        "Un organizationId dans un message ne suffit pas. Le runtime refuse le "
        "multi-organisation jusqu'a l'isolation des API, Kafka, RustFS, secrets, "
        "logs, quotas, sauvegardes et tests de non-fuite.",
        8, y, 85, 9.2,
    )
    y = heading(ax, "Alertes indispensables", y - 3)
    y = bullets(ax, [
        "DLQ non vide ou taux de rejet anormal.",
        "Execution RUNNING sans progression ou heartbeat absent.",
        "Echec de readiness, de nettoyage RustFS ou de sauvegarde.",
        "Croissance Kafka, latence consumer et saturation du stockage.",
        "Echec de rotation des certificats ou des secrets partenaires.",
    ], y, 84)
    box(ax, 8, 15, 84, 15, "Regle d'exploitation",
        "Les corps sensibles ne sont pas journalises. L'historique affiche l'etape "
        "bloquee et l'erreur utile, reliees par correlationId.", "teal", 8.7)
    finish(pdf, fig)


def checklist_page(pdf):
    fig, ax, y = new_page(
        "7. Go / No-Go production et publication",
        "La mise en trafic vient apres les preuves, pas avant",
    )
    y = heading(ax, "Preuves production", y)
    y = bullets(ax, [
        "TLS externe et interne, canaux prives, secrets dans Vault.",
        "Smoke tests synthetiques FHIR JSON/XML, ISO XML, Ed-Fi JSON/NDJSON.",
        "Test Kafka sous le seuil et RustFS au-dessus du seuil.",
        "Test d'un record trop grand, d'un transfert interrompu et d'une DLQ.",
        "Suppression RustFS apres succes et purge d'un echec expire.",
        "Sauvegarde restauree dans un environnement isole.",
        "Readiness et quatre heartbeats OpenHIM au vert.",
        "Contrat de norme signe avec chaque partenaire.",
    ], y, 84, 8.8)
    y = heading(ax, "Publication OpenHIM", y - 2)
    y = bullets(ax, [
        "Depot public : openhim-mediator-iol-standard-packs.",
        "Tag immuable v1.1.0 et images publiees avec digest.",
        "CI Maven, builds Docker et dependency review bloquants.",
        "Topics GitHub openhim-mediator, fhir, iso-20022 et ed-fi.",
        "Verification de la fiche dans la Mediator Library.",
    ], y, 84, 8.8)
    box(ax, 8, 13, 84, 16, "Verdict",
        "L'architecture supprime la dette CSV et prend en charge le volume massif. "
        "L'ouverture multi-organisation reste un No-Go jusqu'aux preuves d'isolation.",
        "green", 9)
    finish(pdf, fig)


def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with PdfPages(OUT) as pdf:
        title_page(pdf)
        architecture_page(pdf)
        transport_page(pdf)
        fhir_page(pdf)
        iso_page(pdf)
        edfi_page(pdf)
        operations_page(pdf)
        checklist_page(pdf)
    print(OUT)


if __name__ == "__main__":
    main()
