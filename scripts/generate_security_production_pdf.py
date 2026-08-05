from pathlib import Path
import sys
import textwrap

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "DOSSIER_SECURITE_PRODUCTION_CHIFFREMENT_TLS.pdf"
PAGE_W, PAGE_H = 8.27, 11.69

COLORS = {
    "ink": "#17212b",
    "muted": "#52606d",
    "line": "#81909e",
    "navy": "#dce8f3",
    "blue": "#dceeff",
    "green": "#def3e5",
    "red": "#fde2df",
    "amber": "#fff0c7",
    "teal": "#ddf4f0",
    "violet": "#ebe3f6",
    "gray": "#edf1f4",
    "white": "#ffffff",
}


def wrap(text, width):
    return textwrap.wrap(
        text,
        width=width,
        replace_whitespace=False,
        break_long_words=False,
        break_on_hyphens=False,
    ) or [""]


def new_page(title, subtitle=None):
    fig, ax = plt.subplots(figsize=(PAGE_W, PAGE_H))
    fig.patch.set_facecolor("white")
    ax.set_xlim(0, 100)
    ax.set_ylim(0, 140)
    ax.axis("off")
    ax.text(6, 135, title, fontsize=17, weight="bold", color=COLORS["ink"], va="top")
    if subtitle:
        ax.text(6, 129.5, subtitle, fontsize=8.5, color=COLORS["muted"], va="top")
        y = 122.5
    else:
        y = 126
    ax.plot([6, 94], [y + 1.5, y + 1.5], color=COLORS["line"], linewidth=0.7)
    return fig, ax, y


def finish(pdf, fig, page_number):
    fig.text(
        0.08,
        0.028,
        "IOL ETL - Sécurité production - 27 juillet 2026",
        fontsize=7,
        color=COLORS["muted"],
    )
    fig.text(
        0.92,
        0.028,
        str(page_number),
        fontsize=7,
        color=COLORS["muted"],
        ha="right",
    )
    pdf.savefig(fig, bbox_inches="tight")
    plt.close(fig)


def paragraph(ax, text, x, y, width=90, size=8.8, leading=3.45, weight="normal", color=None):
    for block in text.split("\n"):
        for line in wrap(block, width):
            ax.text(
                x,
                y,
                line,
                fontsize=size,
                color=color or COLORS["ink"],
                va="top",
                weight=weight,
            )
            y -= leading
        y -= leading * 0.25
    return y


def heading(ax, text, y, x=6, size=10.8):
    ax.text(x, y, text, fontsize=size, weight="bold", color=COLORS["ink"], va="top")
    return y - 4.8


def bullets(ax, items, y, x=8, width=84, size=8.5, leading=3.25):
    for item in items:
        wrapped = wrap(item, width)
        ax.text(x, y, "•", fontsize=size + 1, color=COLORS["ink"], va="top")
        for line in wrapped:
            ax.text(x + 3, y, line, fontsize=size, color=COLORS["ink"], va="top")
            y -= leading
        y -= 0.6
    return y


def box(ax, x, y, w, h, title, body="", color="gray", size=8.2, align="center"):
    patch = FancyBboxPatch(
        (x, y),
        w,
        h,
        boxstyle="round,pad=0.35,rounding_size=0.8",
        linewidth=0.9,
        edgecolor=COLORS["line"],
        facecolor=COLORS[color],
    )
    ax.add_patch(patch)
    tx = x + w / 2 if align == "center" else x + 2
    ha = "center" if align == "center" else "left"
    ax.text(tx, y + h - 2.2, title, fontsize=size, weight="bold",
            color=COLORS["ink"], ha=ha, va="top")
    body_y = y + h - 5.8
    body_width = max(8, int(w * 0.85))
    body_lines = textwrap.wrap(
        body,
        width=body_width,
        replace_whitespace=False,
        break_long_words=True,
        break_on_hyphens=False,
    ) or [""]
    for line in body_lines:
        ax.text(tx, body_y, line, fontsize=size - 1, color=COLORS["muted"],
                ha=ha, va="top")
        body_y -= 2.65


def arrow(ax, x1, y1, x2, y2, label=None, color=None):
    patch = FancyArrowPatch(
        (x1, y1),
        (x2, y2),
        arrowstyle="-|>",
        mutation_scale=10,
        linewidth=1.1,
        color=color or COLORS["line"],
        shrinkA=2,
        shrinkB=2,
    )
    ax.add_patch(patch)
    if label:
        ax.text(
            (x1 + x2) / 2,
            (y1 + y2) / 2 + 1.7,
            label,
            fontsize=6.8,
            color=COLORS["muted"],
            ha="center",
            bbox={"facecolor": "white", "edgecolor": "none", "pad": 0.5},
        )


def status_pill(ax, x, y, text, color):
    box(ax, x, y, 18, 6, text, "", color, 8.2)


def table(ax, x, y, widths, rows, row_h=8, header=True, font_size=7.2):
    total_w = sum(widths)
    current_y = y
    for row_index, row in enumerate(rows):
        bg = COLORS["navy"] if header and row_index == 0 else (
            COLORS["gray"] if row_index % 2 == 0 else COLORS["white"]
        )
        ax.add_patch(
            FancyBboxPatch(
                (x, current_y - row_h),
                total_w,
                row_h,
                boxstyle="square,pad=0",
                linewidth=0.55,
                edgecolor=COLORS["line"],
                facecolor=bg,
            )
        )
        cx = x
        for col_index, value in enumerate(row):
            if col_index:
                ax.plot([cx, cx], [current_y - row_h, current_y],
                        color=COLORS["line"], linewidth=0.45)
            lines = wrap(str(value), max(8, int(widths[col_index] * 1.25)))
            text_y = current_y - 1.4
            for line in lines[:3]:
                ax.text(
                    cx + 1.2,
                    text_y,
                    line,
                    fontsize=font_size,
                    color=COLORS["ink"],
                    va="top",
                    weight="bold" if header and row_index == 0 else "normal",
                )
                text_y -= 2.35
            cx += widths[col_index]
        current_y -= row_h
    return current_y


def title_page(pdf, page):
    fig, ax, _ = new_page(
        "IOL - Dossier de sécurité production",
        "Chiffrement des identifiants métier et TLS entre les composants",
    )
    status_pill(ax, 8, 112, "Décision", "red")
    ax.text(
        8,
        104,
        "NO-GO en l'état actuel",
        fontsize=20,
        weight="bold",
        color="#991b1b",
        va="top",
    )
    y = paragraph(
        ax,
        "La production est bloquée tant que les mots de passe JDBC peuvent être "
        "persistés en clair, que le mot de passe de destination traverse Kafka "
        "et que les liaisons internes sensibles restent non chiffrées.",
        8,
        94,
        75,
        10.2,
        4.3,
        "bold",
    )
    box(ax, 8, 61, 25, 18, "1. Secrets", "Vault Transit, aucune clé dans MongoDB", "green", 9)
    box(ax, 38, 61, 25, 18, "2. Transport", "Aucun secret dans Kafka", "amber", 9)
    box(ax, 68, 61, 25, 18, "3. Réseau", "TLS 1.2/1.3 et mTLS", "blue", 9)
    arrow(ax, 33, 70, 38, 70)
    arrow(ax, 63, 70, 68, 70)
    y = 49
    y = heading(ax, "Finalité du dossier", y, x=8)
    y = bullets(
        ax,
        [
            "Fixer les décisions d'architecture qui ne sont pas négociables.",
            "Décrire les scénarios métier standard et big data.",
            "Organiser une migration sans retour au clair ni au HTTP.",
            "Définir les tests bloquants et les preuves d'audit.",
        ],
        y,
        x=10,
        width=76,
        size=9,
    )
    ax.text(8, 12, "Version 1.0", fontsize=8, color=COLORS["muted"])
    ax.text(8, 8, "Architecture • Développement • Exploitation • Sécurité", fontsize=8,
            color=COLORS["muted"])
    finish(pdf, fig, page)


def decisions_page(pdf, page):
    fig, ax, y = new_page(
        "1. Décisions non négociables",
        "Le secret est protégé pendant tout son cycle de vie, pas seulement dans l'interface",
    )
    y = heading(ax, "Décisions", y)
    y = bullets(
        ax,
        [
            "Les mots de passe utilisateurs restent hachés avec BCrypt.",
            "Les identifiants métier réutilisables sont chiffrés par Vault Transit.",
            "La clé de chiffrement n'est jamais dans MongoDB, Git, une image ou une variable d'environnement.",
            "Kafka ne transporte aucun mot de passe, token, clé privée ou clé d'API.",
            "Le worker obtient le credential de destination au dernier moment par mTLS.",
            "Les identifiants dynamiques par exécution sont préférés quand le SGBD les supporte.",
            "TLS 1.2 minimum, TLS 1.3 préféré, vérification de chaîne et de nom obligatoire.",
            "Une panne Vault ou TLS échoue de façon fermée, sans fallback en clair.",
        ],
        y,
        width=83,
    )
    y = heading(ax, "Choix technologiques", y - 1)
    rows = [
        ["Besoin", "Choix", "Propriété attendue"],
        ["Chiffrement", "Vault Transit", "Clé non exportée, rotation, rewrap"],
        ["Certificats internes", "Vault PKI", "Identité par service, durée courte"],
        ["Certificat public", "ACME / CA publique", "Reconnu par les navigateurs"],
        ["Secrets déployés", "Vault Agent / /run/secrets", "Pas de variable d'environnement"],
        ["Kafka", "mTLS + ACL", "Identité et moindre privilège"],
        ["SGBD", "TLS verify-full", "Confidentialité + anti-usurpation"],
    ]
    table(ax, 6, y, [25, 26, 37], rows, row_h=7.2, font_size=7)
    finish(pdf, fig, page)


def current_risk_page(pdf, page):
    fig, ax, y = new_page(
        "2. Risques constatés dans le dépôt",
        "Le masquage dans l'API ne protège ni MongoDB, ni Kafka, ni les backups",
    )
    box(ax, 5, 101, 20, 16, "MongoDB", "password JDBC persisté en clair", "red", 8.5)
    box(ax, 31, 101, 18, 16, "API Core", "résout et injecte la destination", "amber", 8.5)
    box(ax, 55, 101, 18, 16, "Kafka", "password dans commande / retry / DLQ", "red", 8.5)
    box(ax, 79, 101, 17, 16, "Worker", "secret injecté au moteur", "amber", 8.5)
    arrow(ax, 25, 109, 31, 109)
    arrow(ax, 49, 109, 55, 109)
    arrow(ax, 73, 109, 79, 109)
    y = 90
    y = heading(ax, "Constats de code", y)
    rows = [
        ["Élément", "État actuel", "Niveau"],
        ["Utilisateur IOL", "BCryptPasswordEncoder", "Correct"],
        ["Destination JDBC", "Champ password persistant", "Critique"],
        ["Source JDBC / S3", "Champs password / secretKey", "Critique si utilisés"],
        ["Commande Kafka", "target_connection.password", "Critique"],
        ["SQL Server", "encrypt=false dans URL JDBC", "Critique"],
        ["Réseau interne", "HTTP / Kafka PLAINTEXT", "Critique"],
    ]
    y = table(ax, 6, y, [25, 43, 20], rows, row_h=7.2, font_size=7)
    y = heading(ax, "Conséquence", y - 3)
    paragraph(
        ax,
        "Une lecture de backup MongoDB, un accès historique à Kafka, une DLQ ou "
        "une interception du réseau interne peut exposer des identifiants réels. "
        "La production reste interdite jusqu'à suppression de ces chemins.",
        6,
        y,
        88,
        9,
        3.7,
        "bold",
        "#991b1b",
    )
    finish(pdf, fig, page)


def secret_architecture_page(pdf, page):
    fig, ax, y = new_page(
        "3. Architecture cible des secrets",
        "MongoDB conserve le ciphertext ; le secret n'est délivré que pour une exécution autorisée",
    )
    box(ax, 5, 108, 15, 13, "Admin", "HTTPS", "gray")
    box(ax, 27, 108, 18, 13, "API Core", "validation et policy", "blue")
    box(ax, 54, 108, 19, 13, "Vault Transit", "encrypt / decrypt / rewrap", "green")
    box(ax, 54, 90, 19, 13, "MongoDB", "vault:vN:... seulement", "teal")
    box(ax, 81, 108, 15, 13, "Audit", "sans secret", "gray")
    arrow(ax, 20, 114.5, 27, 114.5)
    arrow(ax, 45, 114.5, 54, 114.5, "mTLS")
    arrow(ax, 54, 96.5, 45, 111, "ciphertext")
    arrow(ax, 73, 114.5, 81, 114.5)
    y = 83
    y = heading(ax, "Modèle persistant", y)
    box(
        ax,
        7,
        58,
        86,
        20,
        "credential",
        "provider=VAULT_TRANSIT  •  keyName=iol-prod-business-credentials  •  "
        "ciphertext=vault:v3:...  •  keyVersion=3  •  schemaVersion=1",
        "gray",
        8.7,
        "left",
    )
    y = 51
    y = heading(ax, "Chiffrement authentifié", y)
    y = paragraph(
        ax,
        "AES-256-GCM et donnée associée stable : "
        "environment | tenantId | connectionId | purpose. "
        "Copier un ciphertext sur une autre connexion doit échouer.",
        6,
        y,
        88,
        8.8,
    )
    y = heading(ax, "Contrat d'API", y - 1)
    bullets(
        ax,
        [
            "La lecture expose passwordConfigured=true/false, jamais *** ni le ciphertext.",
            "Conserver l'ancien secret est une commande explicite.",
            "Une erreur Vault refuse l'écriture ; aucune sauvegarde en clair.",
        ],
        y,
        width=83,
    )
    finish(pdf, fig, page)


def execution_page(pdf, page):
    fig, ax, y = new_page(
        "4. Exécution sans secret dans Kafka",
        "La commande transporte une référence ; le worker s'authentifie au dernier moment",
    )
    box(ax, 4, 99, 17, 15, "API Core", "executionId + destinationId", "blue")
    box(ax, 28, 99, 16, 15, "Kafka", "commande sans secret", "green")
    box(ax, 51, 99, 18, 15, "Worker", "identité mTLS", "teal")
    box(ax, 76, 108, 20, 13, "Credential Broker", "policy + audit + TTL", "green")
    box(ax, 76, 84, 20, 13, "Hop / Spark", "tmpfs puis JDBC TLS", "violet")
    arrow(ax, 21, 106.5, 28, 106.5)
    arrow(ax, 44, 106.5, 51, 106.5)
    arrow(ax, 69, 108.5, 76, 114.5, "mTLS")
    arrow(ax, 76, 111.5, 69, 104, "credential court")
    arrow(ax, 69, 102, 76, 91.5, "tmpfs")
    y = 74
    y = heading(ax, "Commande Kafka autorisée", y)
    box(
        ax,
        7,
        56,
        86,
        15,
        "Payload",
        '{"executionId":"...","workflowId":"...","destinationConnectionId":"...",'
        '"transport":{"mode":"KAFKA|RUSTFS"}}',
        "gray",
        8.4,
        "left",
    )
    y = 48
    y = heading(ax, "Conditions de délivrance", y)
    y = bullets(
        ax,
        [
            "Certificat client iol-pipeline-consumer valide.",
            "Exécution RUNNING et destination identique à l'enregistrement.",
            "Fenêtre temporelle, rate limit, audit et Cache-Control: no-store.",
            "Secret dynamique par exécution ou secret statique déchiffré au dernier moment.",
            "Aucune persistance worker ; suppression du tmpfs à la fin.",
        ],
        y,
        width=82,
    )
    y = heading(ax, "Interdictions", y - 1)
    paragraph(
        ax,
        "Pas de secret dans les arguments, JSON de workflow, variables globales, "
        "logs Hop/Spark, historique, métriques, retry ou DLQ.",
        6,
        y,
        88,
        8.8,
        weight="bold",
        color="#991b1b",
    )
    finish(pdf, fig, page)


def tls_topology_page(pdf, page):
    fig, ax, y = new_page(
        "5. Topologie TLS et mTLS",
        "Une chaîne de confiance complète, du navigateur jusqu'aux moteurs et bases",
    )
    box(ax, 4, 110, 14, 12, "Navigateur", "TLS public", "gray")
    box(ax, 24, 110, 14, 12, "Nginx", "edge", "blue")
    box(ax, 45, 110, 16, 12, "API Core", "mTLS interne", "teal")
    box(ax, 69, 109, 18, 11, "Vault", "Transit + PKI", "green")
    box(ax, 69, 96, 18, 11, "MongoDB", "TLS requis", "navy")
    box(ax, 69, 83, 18, 11, "Kafka", "mTLS + ACL", "amber")
    box(ax, 45, 79, 16, 12, "Worker", "mTLS", "teal")
    box(ax, 69, 71, 18, 11, "RustFS", "HTTPS", "violet")
    box(ax, 69, 54, 18, 11, "Spark", "RPC chiffré", "violet")
    box(ax, 69, 37, 18, 11, "SGBD", "JDBC verify-full", "green")
    arrow(ax, 18, 116, 24, 116, "TLS")
    arrow(ax, 38, 116, 45, 116, "mTLS")
    arrow(ax, 61, 117, 69, 114.5, "mTLS")
    arrow(ax, 61, 113, 69, 101.5, "TLS")
    arrow(ax, 61, 109, 69, 88.5, "mTLS")
    arrow(ax, 69, 88.5, 61, 85, "mTLS")
    arrow(ax, 61, 85, 69, 76.5, "HTTPS")
    arrow(ax, 61, 82, 69, 59.5, "auth + AES-GCM")
    arrow(ax, 78, 54, 78, 48, "JDBC TLS")
    y = 29
    y = heading(ax, "Règles PKI", y)
    bullets(
        ax,
        [
            "Certificat et clé distincts par service ou instance ; SAN DNS obligatoire.",
            "Certificats internes courts, renouvelés automatiquement à 1/3 du TTL.",
            "Clés privées en lecture seule dans /run/secrets, jamais dans l'image.",
            "Ancienne et nouvelle CA coexistent pendant une rotation contrôlée.",
        ],
        y,
        width=83,
        size=8.2,
    )
    finish(pdf, fig, page)


def component_matrix_page(pdf, page):
    fig, ax, y = new_page(
        "6. Matrice de sécurité par composant",
        "Le protocole, l'identité et la validation attendue sont testés séparément",
    )
    rows = [
        ["Client → serveur", "Protection", "Identité / contrôle"],
        ["Navigateur → Nginx", "TLS 1.2/1.3", "CA publique + auth applicative"],
        ["Nginx → API Core", "mTLS", "Certificat client Nginx"],
        ["API Core → Vault", "mTLS", "Workload policy minimale"],
        ["API Core → MongoDB", "TLS requis", "Compte dédié / X.509"],
        ["API/Worker → Kafka", "mTLS", "Principal distinct + ACL"],
        ["Worker → API interne", "mTLS", "Certificat pipeline-consumer"],
        ["API/Worker → RustFS", "HTTPS", "CA vérifiée + policy S3"],
        ["Spark interne", "Auth + RPC AES-GCM", "Secret monté par fichier"],
        ["Moteur → SGBD", "JDBC verify-full", "Compte dédié/dynamique"],
        ["Mediator → OpenHIM", "mTLS", "Certificat médiateur"],
        ["API Core → SMTP", "STARTTLS requis", "Compte technique"],
    ]
    y = table(ax, 5, y, [29, 23, 40], rows, row_h=7.3, font_size=6.9)
    y = heading(ax, "Configurations critiques", y - 3)
    bullets(
        ax,
        [
            "Kafka : plus aucun listener PLAINTEXT après migration.",
            "SQL Server : encrypt=true et trustServerCertificate=false.",
            "PostgreSQL : sslmode=verify-full et règles hostssl.",
            "MongoDB : requireTLS, auth et refus TLS 1.0/1.1.",
            "Spark 3.5.7 : spark.authenticate, crypto version 2, AES/GCM et I/O chiffrée.",
            "RustFS : RUSTFS_TLS_PATH et endpoint https://.",
        ],
        y,
        width=82,
        size=8.1,
    )
    finish(pdf, fig, page)


def hospital_case_page(pdf, page):
    fig, ax, y = new_page(
        "7. Cas réel : hôpital, Oracle vers PostgreSQL",
        "250 000 lignes par heure, transport normal par Kafka, aucun accès source depuis Hop/Spark",
    )
    box(ax, 4, 105, 16, 14, "Oracle", "compte lecture seule + TLS", "navy")
    box(ax, 27, 105, 17, 14, "API Core", "décryptage source en mémoire", "blue")
    box(ax, 51, 105, 16, 14, "Kafka", "données sans secret", "green")
    box(ax, 74, 105, 20, 14, "Worker / Hop", "credential cible court", "teal")
    box(ax, 74, 82, 20, 14, "PostgreSQL", "JDBC verify-full", "green")
    arrow(ax, 20, 112, 27, 112, "JDBC TLS")
    arrow(ax, 44, 112, 51, 112, "mTLS")
    arrow(ax, 67, 112, 74, 112, "mTLS")
    arrow(ax, 84, 105, 84, 96, "JDBC TLS")
    y = 73
    y = heading(ax, "Scénario", y)
    y = bullets(
        ax,
        [
            "API Core teste puis chiffre le mot de passe Oracle avec Vault.",
            "À l'exécution, API Core lit Oracle en fenêtres et referme la connexion.",
            "Kafka transporte les lignes et la commande sans identifiant.",
            "Le worker demande le credential PostgreSQL avec son certificat mTLS.",
            "Hop charge Bronze puis exécute les transformations autorisées.",
            "Le lease est révoqué et le secret temporaire supprimé.",
        ],
        y,
        width=82,
    )
    y = heading(ax, "Preuves de recette", y - 1)
    bullets(
        ax,
        [
            "Zéro occurrence du secret canari dans MongoDB, Kafka, DLQ et logs.",
            "Capture réseau chiffrée ; mauvais SAN et mauvaise CA refusés.",
            "Oracle ne peut pas écrire ; PostgreSQL est limité aux schémas ETL.",
            "Audit relié à executionId, workflowId et identité du worker.",
        ],
        y,
        width=82,
        size=8.3,
    )
    finish(pdf, fig, page)


def bigdata_case_page(pdf, page):
    fig, ax, y = new_page(
        "8. Cas réel : assurance, SQL Server 2,4 To",
        "Bascule big data automatique ; RustFS porte les données, Kafka porte le manifeste",
    )
    box(ax, 4, 101, 18, 14, "SQL Server", "encrypt=true, certificat vérifié", "navy")
    box(ax, 29, 101, 18, 14, "API Core", "fenêtres d'extraction", "blue")
    box(ax, 55, 110, 17, 13, "RustFS", "objets via HTTPS", "violet")
    box(ax, 55, 88, 17, 13, "Kafka", "manifeste sans secret", "green")
    box(ax, 80, 101, 16, 14, "Spark", "RPC chiffré", "teal")
    box(ax, 80, 74, 16, 14, "DWH", "JDBC TLS", "green")
    arrow(ax, 22, 108, 29, 108, "JDBC TLS")
    arrow(ax, 47, 111, 55, 116.5, "HTTPS")
    arrow(ax, 47, 105, 55, 94.5, "mTLS")
    arrow(ax, 72, 116.5, 80, 111, "HTTPS")
    arrow(ax, 72, 94.5, 80, 105, "commande")
    arrow(ax, 88, 101, 88, 88, "JDBC TLS")
    y = 65
    y = heading(ax, "Garanties", y)
    y = bullets(
        ax,
        [
            "Hop et Spark ne se connectent jamais directement à la source.",
            "RustFS reçoit les objets chiffrés en transit et limités au préfixe de l'exécution.",
            "Kafka conserve uniquement le manifeste signé et les paramètres techniques.",
            "Le worker obtient la destination par le Credential Broker mTLS.",
            "Les objets temporaires sont supprimés après succès global et ACK Kafka.",
            "Une erreur conserve temporairement l'objet pour diagnostic et rejeu selon la rétention.",
        ],
        y,
        width=82,
    )
    y = heading(ax, "Tests volumétriques", y - 1)
    paragraph(
        ax,
        "Mesurer le débit Kafka SSL, RustFS HTTPS, Spark chiffré et JDBC TLS. "
        "Les seuils de bascule sont validés uniquement avec la sécurité active.",
        6,
        y,
        88,
        8.8,
        weight="bold",
    )
    finish(pdf, fig, page)


def rotation_failure_page(pdf, page):
    fig, ax, y = new_page(
        "9. Rotations et pannes",
        "La plateforme doit rester sûre quand une clé, un certificat ou Vault change d'état",
    )
    box(ax, 5, 104, 27, 17, "Rotation Transit", "rotate → nouvelles écritures → rewrap → vérification", "green", 8.6)
    box(ax, 37, 104, 27, 17, "Rotation CA", "double trust → nouveaux certs → retrait ancienne CA", "blue", 8.6)
    box(ax, 69, 104, 27, 17, "Panne Vault", "jobs valides finissent, nouveaux jobs attendent", "amber", 8.6)
    y = 91
    y = heading(ax, "Règles de continuité", y)
    y = bullets(
        ax,
        [
            "Ne relever la version minimale de clé qu'après rewrap complet et preuve de restauration.",
            "Distribuer le nouveau trust bundle avant les nouveaux certificats.",
            "Un job déjà lancé peut finir tant que son lease reste valide.",
            "Aucune nouvelle exécution ne démarre si le credential ne peut être délivré.",
            "Le retry est borné, idempotent et visible dans le monitoring.",
            "Aucun secret local de secours, aucun retour HTTP et aucune désactivation de validation.",
        ],
        y,
        width=83,
    )
    y = heading(ax, "Restauration après sinistre", y - 1)
    y = paragraph(
        ax,
        "MongoDB et Vault se restaurent comme un couple cohérent. De nouveaux "
        "certificats sont émis, les secrets statiques sont rotatés et deux "
        "canaris, standard puis big data, valident la reprise.",
        6,
        y,
        88,
        8.8,
    )
    y = heading(ax, "Séparation des responsabilités", y - 1)
    paragraph(
        ax,
        "Le rôle capable de restaurer MongoDB ne doit pas, seul, restaurer ou "
        "administrer les clés Vault. Les opérations critiques exigent un double contrôle.",
        6,
        y,
        88,
        8.8,
        weight="bold",
    )
    finish(pdf, fig, page)


def migration_page(pdf, page):
    fig, ax, y = new_page(
        "10. Migration séquencée",
        "Chaque phase possède un Gate ; aucun rollback ne réactive le stockage clair ou HTTP",
    )
    phases = [
        ("0", "Inventaire", "Secrets, liaisons, propriétaires", "gray"),
        ("1", "Vault", "HA, Transit, PKI, backup", "green"),
        ("2", "Chiffrement", "Dual-read temporaire, migration, $unset", "teal"),
        ("3", "Kafka", "Référence seule + Credential Broker", "amber"),
        ("4", "TLS", "Composant par composant", "blue"),
        ("5", "Répétition", "Pannes, rotations, restauration", "violet"),
        ("6", "Canari", "Charge progressive et observation", "green"),
    ]
    top = 104
    for index, (number, title, body, color) in enumerate(phases):
        row = index // 2
        col = index % 2
        x = 6 + col * 46
        yy = top - row * 22
        box(ax, x, yy, 42, 16, f"Phase {number} — {title}", body, color, 8.2, "left")
        if index < len(phases) - 1:
            if col == 0:
                arrow(ax, x + 42, yy + 8, x + 46, yy + 8)
            else:
                arrow(ax, x + 21, yy, 6 + 21, yy - 6)
    y = 21
    y = heading(ax, "Ordre TLS d'un composant", y)
    paragraph(
        ax,
        "Émettre → distribuer la confiance → activer le serveur TLS → migrer "
        "les clients → observer → fermer le listener en clair.",
        6,
        y,
        88,
        8.7,
        weight="bold",
    )
    finish(pdf, fig, page)


def test_page(pdf, page):
    fig, ax, y = new_page(
        "11. Recette bloquante",
        "Le succès fonctionnel ne suffit pas : les secrets, le réseau et les scénarios d'échec sont inspectés",
    )
    rows = [
        ["Test", "Résultat requis"],
        ["Workflow standard", "Données Kafka, zéro secret, destination TLS"],
        ["Workflow big data", "RustFS HTTPS, manifeste Kafka sans secret"],
        ["Retry / DLQ", "Reprise idempotente, aucune donnée sensible"],
        ["Vault indisponible", "Échec contrôlé, aucune sauvegarde/fallback clair"],
        ["Certificat révoqué", "Kafka et Credential Broker refusent le worker"],
        ["Mauvaise CA / mauvais SAN", "Connexion refusée"],
        ["Rotation Transit", "Nouvelles écritures + rewrap + restauration"],
        ["Rotation CA", "Aucune interruption après répétition préprod"],
        ["Recherche canari", "Zéro occurrence dans tous les supports"],
    ]
    y = table(ax, 6, y, [30, 58], rows, row_h=7.8, font_size=7.1)
    y = heading(ax, "Supports inspectés", y - 3)
    y = bullets(
        ax,
        [
            "MongoDB et backups ; Kafka, retry et DLQ ; logs API, worker, Hop, Spark et OpenHIM.",
            "Traces, métriques, fichiers temporaires, volumes, variables de conteneur et artefacts CI.",
            "Capture réseau, ports ouverts, versions TLS, chaîne, SAN, expiration et certificat client.",
        ],
        y,
        width=82,
        size=8.3,
    )
    y = heading(ax, "Performance", y - 1)
    paragraph(
        ax,
        "Les p95 Vault, handshakes, Kafka SSL, RustFS HTTPS, JDBC TLS et temps "
        "global sont mesurés avec la sécurité active. Une mesure en clair n'est "
        "pas une référence de production.",
        6,
        y,
        88,
        8.6,
        weight="bold",
    )
    finish(pdf, fig, page)


def go_nogo_page(pdf, page):
    fig, ax, y = new_page(
        "12. Autorisation de production",
        "Tous les critères GO sont cumulatifs ; un seul NO-GO bloque la bascule",
    )
    box(ax, 5, 109, 42, 12, "GO", "preuves complètes et recette signée", "green", 9)
    box(ax, 53, 109, 42, 12, "NO-GO", "un seul défaut critique suffit", "red", 9)
    y = 101
    y = heading(ax, "GO obligatoire", y)
    y = bullets(
        ax,
        [
            "Zéro secret en clair dans MongoDB, Kafka, DLQ, logs, traces et métriques.",
            "Vault HA, sauvegardé, restauré et soumis au moindre privilège.",
            "Toutes les liaisons prévues chiffrées avec vérification du nom.",
            "Kafka sans listener PLAINTEXT ; SQL Server sans encrypt=false.",
            "Spark authentifié, RPC et données temporaires chiffrés.",
            "Rotations Transit et CA réussies en préproduction.",
            "Workflows standard et big data réussis de bout en bout.",
            "Panne Vault, révocation worker et restauration testées.",
            "Procès-verbal signé par sécurité, exploitation et propriétaire.",
        ],
        y,
        width=82,
        size=8.25,
    )
    y = heading(ax, "NO-GO immédiat", y - 1)
    bullets(
        ax,
        [
            "Secret persistant ou présent dans Kafka.",
            "Validation de certificat désactivée ou port sensible en clair.",
            "Clé privée dans Git, une image ou une variable d'environnement.",
            "Fallback automatique vers HTTP ou mot de passe en clair.",
            "Rotation ou restauration non testée ; défaut critique/haut ouvert.",
        ],
        y,
        width=82,
        size=8.25,
    )
    finish(pdf, fig, page)


def work_items_page(pdf, page):
    fig, ax, y = new_page(
        "13. Travaux à engager dans le dépôt",
        "Priorité : éliminer l'exposition des secrets avant le basculement TLS global",
    )
    rows = [
        ["Zone", "Travail principal"],
        ["DestinationConnection", "Remplacer password par credential chiffré"],
        ["SourceMetadata", "Chiffrer JDBC password et S3 secretKey"],
        ["DestinationConnectionService", "Transit + passwordConfigured"],
        ["KafkaPipelineEventService", "Supprimer target_connection.password"],
        ["Credential Broker", "Nouvelle API interne mTLS, TTL, policy, audit"],
        ["PipelineOrchestrator", "Référence Kafka, credential à l'exécution, tmpfs"],
        ["JDBC", "Profils TLS ; supprimer encrypt=false"],
        ["Compose production", "Vault, secrets montés, réseaux et endpoints TLS"],
        ["Kafka/Mongo/Postgres/RustFS", "Activer TLS et fermer les protocoles clairs"],
        ["Spark/OpenHIM/SMTP", "Durcissement propre à chaque protocole"],
        ["CI/CD", "Secret scan, tests TLS, rotation et canaris"],
    ]
    y = table(ax, 5, y, [30, 62], rows, row_h=7.5, font_size=6.95)
    y = heading(ax, "Ordre d'implémentation", y - 3)
    bullets(
        ax,
        [
            "1. Vault/PKI préproduction.",
            "2. Chiffrement et migration des secrets persistés.",
            "3. Suppression des secrets dans Kafka.",
            "4. TLS/mTLS composant par composant.",
            "5. Recette complète, répétition puis canari.",
        ],
        y,
        width=80,
        size=8.25,
    )
    finish(pdf, fig, page)


def references_page(pdf, page):
    fig, ax, y = new_page(
        "14. Références et règle finale",
        "Les choix sont fondés sur les guides officiels des composants présents dans la plateforme",
    )
    y = heading(ax, "Références principales", y)
    y = bullets(
        ax,
        [
            "NIST SP 800-52 Rev. 2 — sélection et configuration de TLS.",
            "OWASP Cryptographic Storage Cheat Sheet — chiffrement authentifié et cycle de vie des clés.",
            "HashiCorp Vault — Transit, Database Secrets Engine et PKI rotation primitives.",
            "Apache Kafka 3.4 — SSL/SASL, authentification et ACL.",
            "Apache Spark 3.5.7 — authentification, chiffrement RPC, disque local et UI SSL.",
            "MongoDB — requireTLS et migration roulante vers TLS.",
            "PostgreSQL — SSL, hostssl et verify-full.",
            "Spring Boot — SSL Bundles et rechargement des certificats.",
            "RustFS — RUSTFS_TLS_PATH et endpoint HTTPS.",
            "Docker Compose — secrets montés par fichier.",
        ],
        y,
        width=82,
        size=8.5,
    )
    y = heading(ax, "Règle finale", y - 2)
    box(
        ax,
        8,
        36,
        84,
        25,
        "La plateforme doit échouer de façon sûre",
        "Une indisponibilité, une expiration ou une erreur de configuration ne "
        "doit jamais réactiver un secret en clair, un transport HTTP, un listener "
        "Kafka PLAINTEXT ou une validation de certificat désactivée.",
        "red",
        9.4,
    )
    ax.text(
        50,
        22,
        "La production commence après les preuves, pas avant.",
        fontsize=12,
        weight="bold",
        color=COLORS["ink"],
        ha="center",
    )
    finish(pdf, fig, page)


def generate():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    pages = [
        title_page,
        decisions_page,
        current_risk_page,
        secret_architecture_page,
        execution_page,
        tls_topology_page,
        component_matrix_page,
        hospital_case_page,
        bigdata_case_page,
        rotation_failure_page,
        migration_page,
        test_page,
        go_nogo_page,
        work_items_page,
        references_page,
    ]
    with PdfPages(OUT) as pdf:
        for page_number, renderer in enumerate(pages, start=1):
            renderer(pdf, page_number)
    print(OUT)


def generate_previews(output_dir):
    output_dir.mkdir(parents=True, exist_ok=True)

    class PreviewWriter:
        def __init__(self, path):
            self.path = path

        def savefig(self, fig, **_kwargs):
            fig.savefig(self.path, dpi=150, bbox_inches="tight")

    selected = [
        (1, title_page),
        (2, decisions_page),
        (3, current_risk_page),
        (4, secret_architecture_page),
        (5, execution_page),
        (6, tls_topology_page),
        (7, component_matrix_page),
        (8, hospital_case_page),
        (9, bigdata_case_page),
        (10, rotation_failure_page),
        (11, migration_page),
        (12, test_page),
        (13, go_nogo_page),
        (14, work_items_page),
        (15, references_page),
    ]
    for page_number, renderer in selected:
        renderer(PreviewWriter(output_dir / f"page-{page_number:02d}.png"), page_number)
    print(output_dir)


if __name__ == "__main__":
    if "--preview" in sys.argv:
        generate_previews(ROOT / ".tmp" / "iol-security-pdf-preview")
    else:
        generate()
