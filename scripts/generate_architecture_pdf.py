from pathlib import Path
import textwrap

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "ARCHITECTURE_ETL_LOCAL_SPARK.pdf"

PAGE_W, PAGE_H = 8.27, 11.69

COLORS = {
    "ink": "#17212b",
    "muted": "#52616f",
    "line": "#8aa1b2",
    "blue": "#d9ecff",
    "green": "#dff4e5",
    "amber": "#fff0c2",
    "red": "#ffe2df",
    "gray": "#eef2f5",
    "purple": "#eadfff",
    "teal": "#dff7f4",
}


def page(pdf, title, kicker=None):
    fig, ax = plt.subplots(figsize=(PAGE_W, PAGE_H))
    ax.set_xlim(0, 100)
    ax.set_ylim(0, 140)
    ax.axis("off")
    ax.text(6, 134, title, fontsize=18, weight="bold", color=COLORS["ink"], va="top")
    if kicker:
        ax.text(6, 128, kicker, fontsize=9, color=COLORS["muted"], va="top")
        y = 121
    else:
        y = 124
    return fig, ax, y


def finish(pdf, fig):
    pdf.savefig(fig, bbox_inches="tight")
    plt.close(fig)


def wrap(text, width):
    return textwrap.wrap(text, width=width, replace_whitespace=False) or [""]


def para(ax, text, x, y, width=92, size=9.2, color=None, weight="normal", leading=3.65):
    color = color or COLORS["ink"]
    for block in text.split("\n"):
        for line in wrap(block, width):
            ax.text(x, y, line, fontsize=size, color=color, weight=weight, va="top")
            y -= leading
        y -= leading * 0.35
    return y


def bullets(ax, items, x, y, width=86, size=8.8):
    for item in items:
        lines = wrap(item, width)
        ax.text(x, y, "•", fontsize=size + 2, color=COLORS["ink"], va="top")
        ax.text(x + 3, y, lines[0], fontsize=size, color=COLORS["ink"], va="top")
        y -= 3.4
        for extra in lines[1:]:
            ax.text(x + 3, y, extra, fontsize=size, color=COLORS["ink"], va="top")
            y -= 3.4
        y -= 0.8
    return y


def section(ax, text, x, y):
    ax.text(x, y, text, fontsize=11, weight="bold", color=COLORS["ink"], va="top")
    return y - 5


def box(ax, x, y, w, h, title, body="", fill="gray", size=8.6):
    patch = FancyBboxPatch(
        (x, y), w, h,
        boxstyle="round,pad=0.45,rounding_size=1.2",
        linewidth=1.1,
        edgecolor=COLORS["line"],
        facecolor=COLORS[fill],
    )
    ax.add_patch(patch)
    ax.text(x + w / 2, y + h - 2.6, title, ha="center", va="top",
            fontsize=size, weight="bold", color=COLORS["ink"])
    if body:
        body_y = y + h - 6.2
        for line in wrap(body, max(12, int(w * 1.15))):
            ax.text(x + w / 2, body_y, line, ha="center", va="top",
                    fontsize=size - 1.0, color=COLORS["muted"])
            body_y -= 2.9


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
        ax.text((x1 + x2) / 2, (y1 + y2) / 2 + 2, label,
                fontsize=7.5, color=COLORS["muted"], ha="center")


def note(ax, x, y, text, w=88, fill="gray"):
    box(ax, x, y - 13, w, 13, "A retenir", text, fill=fill, size=8.8)
    return y - 17


def title_page(pdf):
    fig, ax, y = page(
        pdf,
        "Architecture ETL IOL",
        "Lecture JDBC directe en LOCAL et SPARK - 19 juillet 2026",
    )
    y = para(ax, "Ce document explique le fonctionnement de la plateforme a partir du code reel du depot. "
                 "Il repond aussi a une question de conception produit : quelles decisions techniques doivent etre visibles pour l'utilisateur final ?", 6, y, 88, 10.2)
    y = section(ax, "Reponse en une phrase", 6, y - 2)
    y = para(ax, "Une source JDBC n'est plus convertie en CSV. LOCAL la lit directement par lots et publie Bronze via une table de transit ; SPARK la lit directement en partitions. api-core mesure la charge et choisit le runtime avant l'execution.", 6, y, 88, 10.0)
    y = section(ax, "Position UX recommandee", 6, y - 2)
    y = bullets(ax, [
        "Ne pas demander a l'utilisateur metier de choisir entre Hop et Spark.",
        "Mesurer automatiquement le volume ; la saisie manuelle reste reservee aux administrateurs.",
        "Garder les details Hop, spark-submit, partitions et jars JDBC dans un mode avance/admin.",
    ], 8, y, 84, 9.4)
    y = section(ax, "Fichiers audites", 6, y - 2)
    y = bullets(ax, [
        "SourceLoadEstimatorService.java : mesure, choix conservateur et partitions JDBC automatiques.",
        "SourceDataTransportService.java : interdit toute materialisation d'une source JDBC.",
        "PipelineOrchestrator.java : choix LOCAL/SPARK, lancement Hop ou spark-submit, statuts.",
        "spark_etl.py : lecture JDBC directe par Spark, Bronze, Silver, Gold.",
        "moteur_universel.py et Hop Global_Config : chemin LOCAL Bronze/Silver/Gold.",
    ], 8, y, 84, 8.8)
    finish(pdf, fig)


def direct_answers(pdf):
    fig, ax, y = page(pdf, "Reponses directes")
    y = section(ax, "1. JDBC est-il encore converti en CSV ?", 6, y)
    y = para(ax, "Non. Le chemin LOCAL et le chemin SPARK conservent tous les deux la source JDBC. Kafka transporte seulement la commande et la metadata de connexion ; les lignes vont directement de la base source vers le moteur puis vers la destination.", 6, y, 90)
    y = bullets(ax, [
        "LOCAL : moteur_universel.py lit JDBC par lots bornes en memoire.",
        "SPARK : les executors lisent JDBC directement en partitions.",
        "SourceDataTransportService refuse explicitement de materialiser JDBC.",
        "Les fichiers CSV natifs et les API restent des protocoles distincts.",
    ], 8, y, 86)

    y = section(ax, "2. Comment eviter une table Bronze partielle ?", 6, y - 2)
    y = bullets(ax, [
        "LOCAL ecrit chaque lot dans une table de transit _iol_stage_*.",
        "La table finale reste intacte pendant toute la lecture source.",
        "La table de transit est promue seulement apres succes complet.",
        "En echec gere, la table de transit est supprimee et l'ancien Bronze reste disponible.",
    ], 8, y, 86)

    y = section(ax, "3. L'utilisateur doit-il savoir qu'il y a Hop ou Spark ?", 6, y - 2)
    y = para(ax, "Non. Le backend mesure la charge. L'utilisateur configure la source, la destination et les regles. Les noms Hop/Spark, le volume manuel et les partitions sont reserves aux administrateurs, au support et aux logs.", 6, y, 90)
    finish(pdf, fig)


def global_architecture(pdf):
    fig, ax, y = page(pdf, "Vue generale de la plateforme")
    box(ax, 5, 104, 15, 12, "frontend", "configuration et suivi", "blue")
    box(ax, 27, 104, 15, 12, "api-core", "validation, payload, logs", "green")
    box(ax, 49, 104, 15, 12, "Kafka", "commandes, metadata, status", "amber")
    box(ax, 71, 104, 20, 12, "pipeline-consumer", "execute ou soumet", "teal")
    arrow(ax, 20, 110, 27, 110)
    arrow(ax, 42, 110, 49, 110)
    arrow(ax, 64, 110, 71, 110)

    box(ax, 27, 84, 15, 10, "MongoDB", "workflows, logs, users", "gray")
    box(ax, 49, 84, 15, 10, "RustFS", "fichiers non-JDBC", "purple")
    arrow(ax, 34.5, 104, 34.5, 94)
    arrow(ax, 56.5, 104, 56.5, 94)
    arrow(ax, 64, 89, 71, 106, "reference")

    box(ax, 68, 73, 12, 10, "LOCAL", "Hop", "green")
    box(ax, 84, 73, 12, 10, "SPARK", "spark-submit", "blue")
    arrow(ax, 78, 104, 74, 83)
    arrow(ax, 82, 104, 90, 83)

    box(ax, 57, 55, 16, 10, "Apache Hop", "orchestration locale", "green")
    box(ax, 75, 55, 16, 10, "moteur_universel", "Bronze local", "green")
    box(ax, 84, 39, 12, 10, "spark_etl.py", "PySpark", "blue")
    arrow(ax, 74, 73, 65, 65)
    arrow(ax, 73, 60, 75, 60)
    arrow(ax, 90, 73, 90, 49)

    box(ax, 39, 28, 18, 11, "Destination", "Bronze / Silver / Gold", "amber")
    arrow(ax, 83, 55, 57, 35)
    arrow(ax, 84, 42, 57, 33)

    box(ax, 5, 28, 17, 11, "Statuts", "SUCCESS, FAILED, DLQ", "red")
    arrow(ax, 71, 104, 14, 39, "retour Kafka")
    arrow(ax, 14, 28, 29, 104, "monitoring")

    y = 20
    para(ax, "La plateforme est decouplee : l'API ne fait pas l'execution complete, le consumer ne decide pas la configuration metier, et la destination reste le lieu final des couches medaillon.", 6, y, 90, 8.8)
    finish(pdf, fig)


def local_flow(pdf):
    fig, ax, y = page(pdf, "Chemin LOCAL : JDBC direct et publication sure")
    box(ax, 5, 101, 17, 10, "api-core", "metadata seulement", "green")
    box(ax, 29, 101, 17, 10, "Kafka", "commande", "amber")
    box(ax, 53, 101, 18, 10, "consumer", "lance Hop", "teal")
    box(ax, 78, 101, 16, 10, "Hop", "orchestration", "green")
    arrow(ax, 22, 106, 29, 106)
    arrow(ax, 46, 106, 53, 106)
    arrow(ax, 71, 106, 78, 106)

    box(ax, 7, 72, 18, 11, "Source JDBC", "aucun fichier intermediaire", "gray")
    box(ax, 38, 72, 22, 11, "moteur_universel", "lecture par lots de 50 000", "blue")
    box(ax, 73, 72, 20, 11, "Table transit", "_iol_stage_*", "purple")
    arrow(ax, 25, 77.5, 38, 77.5, "JDBC direct")
    arrow(ax, 60, 77.5, 73, 77.5, "lots")
    arrow(ax, 86, 101, 55, 83)

    box(ax, 12, 43, 20, 11, "Bronze final", "publie apres succes", "amber")
    box(ax, 41, 43, 18, 11, "Silver", "par source", "blue")
    box(ax, 68, 43, 18, 11, "Gold", "global workflow", "green")
    arrow(ax, 80, 72, 30, 54, "promotion")
    arrow(ax, 32, 48.5, 41, 48.5)
    arrow(ax, 59, 48.5, 68, 48.5)

    y = 29
    y = bullets(ax, [
        "Aucune ligne JDBC ne traverse Kafka ou RustFS.",
        "La lecture par lots borne la memoire du processus local.",
        "La table finale n'est modifiee qu'apres la lecture complete de la source.",
        "Une erreur geree supprime la table de transit et conserve l'ancien Bronze.",
    ], 8, y, 84, 8.8)
    finish(pdf, fig)


def spark_flow(pdf):
    fig, ax, y = page(pdf, "Chemin SPARK : JDBC lu directement")
    box(ax, 5, 104, 16, 10, "api-core", "metadata Kafka", "green")
    box(ax, 28, 104, 18, 10, "pipeline-consumer", "spark-submit", "teal")
    box(ax, 53, 104, 17, 10, "spark_etl.py", "driver", "blue")
    arrow(ax, 21, 109, 28, 109)
    arrow(ax, 46, 109, 53, 109)

    box(ax, 8, 76, 18, 12, "Source JDBC", "table/requete source", "gray")
    box(ax, 36, 88, 18, 10, "Executor 1", "partition 1", "blue")
    box(ax, 36, 72, 18, 10, "Executor 2", "partition 2", "blue")
    box(ax, 36, 56, 18, 10, "Executor n", "partition n", "blue")
    arrow(ax, 26, 82, 36, 93)
    arrow(ax, 26, 82, 36, 77)
    arrow(ax, 26, 82, 36, 61)
    arrow(ax, 61, 104, 50, 98)

    box(ax, 65, 88, 20, 10, "Bronze views", "bronze_0, bronze_1", "purple")
    box(ax, 65, 70, 20, 10, "Silver", "SQL destination ou distribue", "purple")
    box(ax, 65, 52, 20, 10, "Gold", "SQL destination ou distribue", "purple")
    arrow(ax, 54, 88, 65, 93)
    arrow(ax, 75, 88, 75, 80)
    arrow(ax, 75, 70, 75, 62)

    box(ax, 37, 31, 20, 11, "Destination", "tables Bronze/Silver/Gold", "amber")
    arrow(ax, 75, 88, 57, 40)
    arrow(ax, 75, 70, 57, 37)
    arrow(ax, 75, 52, 57, 34)

    y = 22
    y = bullets(ax, [
        "Le payload conserve seulement les informations JDBC necessaires : aucune ligne source n'est transportee.",
        "api-core detecte une colonne numerique/temporelle et calcule automatiquement les bornes quand le volume est grand.",
        "Le partitionnement Spark utilise partitionColumn, lowerBound, upperBound et numPartitions, avec un plafond de securite.",
        "Silver et Gold peuvent rester en SQL destination ou passer en traitement distribue si le volume le justifie.",
    ], 8, y, 84, 8.8)
    finish(pdf, fig)


def medallion(pdf):
    fig, ax, y = page(pdf, "Bronze, Silver, Gold")
    box(ax, 7, 96, 18, 10, "Source A", "JDBC/API/fichier", "gray")
    box(ax, 7, 72, 18, 10, "Source B", "JDBC/API/fichier", "gray")
    box(ax, 33, 96, 18, 10, "Bronze A", "atterrissage", "amber")
    box(ax, 33, 72, 18, 10, "Bronze B", "atterrissage", "amber")
    box(ax, 59, 96, 18, 10, "Silver A", "standardisation", "blue")
    box(ax, 59, 72, 18, 10, "Silver B", "standardisation", "blue")
    box(ax, 42, 43, 24, 12, "Gold global", "consolidation metier du workflow", "green")
    arrow(ax, 25, 101, 33, 101)
    arrow(ax, 25, 77, 33, 77)
    arrow(ax, 51, 101, 59, 101)
    arrow(ax, 51, 77, 59, 77)
    arrow(ax, 68, 96, 54, 55)
    arrow(ax, 68, 72, 54, 55)

    y = 29
    y = section(ax, "Interpretation", 6, y)
    y = bullets(ax, [
        "Bronze garde une trace proche de la source : projection, nettoyage technique des noms de colonnes, batch et watermark.",
        "Silver est par source : typage, nettoyage, normalisation, regles de qualite.",
        "Gold est au niveau workflow : il combine les Silver pour produire la table metier finale.",
    ], 8, y, 84, 8.8)
    finish(pdf, fig)


def ux_page(pdf):
    fig, ax, y = page(pdf, "Ce que l'utilisateur doit voir")
    box(ax, 6, 98, 22, 13, "Utilisateur metier", "source, destination, colonnes, regles, frequence", "blue")
    box(ax, 39, 98, 22, 13, "Interface", "source + resultat attendu", "green")
    box(ax, 72, 98, 20, 13, "Plateforme", "mesure et choisit", "purple")
    arrow(ax, 28, 104.5, 39, 104.5)
    arrow(ax, 61, 104.5, 72, 104.5)

    y = 83
    y = section(ax, "Recommandation", 6, y)
    y = para(ax, "Le parcours principal ne demande ni Hop, ni Spark, ni volume estime. api-core mesure la charge et applique une politique conservatrice avant l'execution.", 6, y, 90)
    y = bullets(ax, [
        "Utilisateur standard : source, destination, colonnes, regles, frequence et resultats.",
        "Mode avance/admin : volume de repli, execution_engine, partitions et transport des fichiers.",
        "Logs techniques : executionMode, loadAssessment et origine du partitionnement.",
    ], 8, y, 86)

    y = section(ax, "Pourquoi", 6, y - 1)
    y = para(ax, "Un utilisateur metier doit raisonner en qualite de donnees, frequence, volume, resultat et controle. Les noms Hop/Spark servent surtout au diagnostic, au support et a l'exploitation.", 6, y, 90)
    finish(pdf, fig)


def reliability_page(pdf):
    fig, ax, y = page(pdf, "Fiabilite : Kafka, RustFS, statuts, watermarks")
    box(ax, 6, 103, 18, 10, "Kafka", "commandes et metadata", "amber")
    box(ax, 31, 103, 18, 10, "Verrou", "par destination", "gray")
    box(ax, 56, 103, 18, 10, "Execution", "Hop ou Spark", "green")
    box(ax, 81, 103, 13, 10, "Status", "Kafka", "red")
    arrow(ax, 24, 108, 31, 108)
    arrow(ax, 49, 108, 56, 108)
    arrow(ax, 74, 108, 81, 108)

    y = 87
    y = bullets(ax, [
        "Les commandes sont priorisees par topics Kafka : high, normal, low.",
        "Les lignes JDBC ne voyagent pas dans Kafka/RustFS ; les moteurs les lisent directement.",
        "Les vraies sources fichier peuvent encore voyager par morceaux ou reference RustFS avec verification du hash.",
        "Le verrou d'execution evite que deux workflows ecrivent en meme temps dans la meme destination.",
        "LOCAL utilise une table Bronze de transit avant publication de la table finale.",
        "Les watermarks sont emis par les moteurs, relus dans les logs, puis persistes par api-core.",
        "En echec, le statut contient l'etape probable : PREPARATION, EXTRACTION, BRONZE, SILVER, GOLD, DESTINATION ou HOP.",
    ], 8, y, 86)

    y = note(ax, 6, y - 2, "Cette boucle rend l'execution observable : l'utilisateur voit un statut metier, pendant que les details techniques restent exploitables par l'equipe.", 88, "teal")
    finish(pdf, fig)


def interop_page(pdf):
    fig, ax, y = page(pdf, "Interop et extensions")
    y = para(ax, "La plateforme contient aussi un chemin d'interoperabilite autour d'OpenHIM et du mediateur Node. Ce chemin n'annule pas l'architecture ETL : il ajoute un point d'entree/sortie pour les echanges hospitaliers.", 6, y, 90)
    box(ax, 7, 88, 18, 10, "Systeme externe", "hopital/API", "gray")
    box(ax, 31, 88, 16, 10, "OpenHIM", "gateway", "blue")
    box(ax, 53, 88, 17, 10, "iol-mediator", "normalise/commande", "green")
    box(ax, 76, 88, 16, 10, "api-core", "prepare execution", "green")
    arrow(ax, 25, 93, 31, 93)
    arrow(ax, 47, 93, 53, 93)
    arrow(ax, 70, 93, 76, 93)
    box(ax, 31, 61, 22, 10, "Pipeline ETL", "Kafka -> consumer -> Hop/Spark", "purple")
    box(ax, 63, 61, 22, 10, "Gold", "donnee finale", "amber")
    arrow(ax, 84, 88, 42, 71)
    arrow(ax, 53, 66, 63, 66)
    y = 45
    y = bullets(ax, [
        "Inbound : un message externe est normalise puis envoye dans le pipeline ETL.",
        "Outbound : une fois le Gold pret, la plateforme peut publier/livrer vers un systeme externe.",
        "Pour l'utilisateur, cela reste un flux de reception/livraison ; OpenHIM et le mediateur sont des details d'infrastructure.",
    ], 8, y, 86)
    finish(pdf, fig)


def code_page(pdf):
    fig, ax, y = page(pdf, "Ancrages dans le code")
    y = bullets(ax, [
        "SourceLoadEstimatorService.java : mesure JDBC avec timeout, choisit une colonne de partition et calcule automatiquement MIN/MAX et le nombre de partitions.",
        "KafkaPipelineEventService.java : api-core choisit executionMode=SPARK selon le diagnostic ou une etape distribuee.",
        "SourceDataTransportService.java : toute source JDBC est marquee DIRECT_JDBC et ne peut plus etre materialisee.",
        "PipelineOrchestrator.java : verifie le mode final et lance Hop ou spark-submit.",
        "spark_etl.py : read_jdbc_source() utilise spark.read.format('jdbc') et ajoute partitionColumn/lowerBound/upperBound/numPartitions quand le partitionnement est active.",
        "spark_etl.py : process() ecrit Bronze, puis execute Silver et Gold en SQL destination ou traitement distribue selon execution_engine.",
        "Hop Global_Config : wf_main_ingestion.hwf appelle process_one_source.hwf pour chaque source, puis gold_elt_dynamique.hpl ; process_one_source.hwf enchaine bronze_loop.hpl puis silver_loop.hpl.",
        "moteur_universel.py : JDBC direct par lots, table _iol_stage_*, promotion apres succes, mappings et watermark.",
    ], 8, y, 84, 8.55)
    y = section(ax, "Conclusion", 6, y - 1)
    y = para(ax, "La bonne abstraction produit est : l'utilisateur choisit l'intention, la plateforme mesure puis choisit le runtime. LOCAL et SPARK lisent JDBC directement ; seule la maniere de calculer change.", 6, y, 90, 9.5)
    finish(pdf, fig)


def architecture_v2_title(pdf):
    fig, ax, y = page(
        pdf,
        "Architecture ETL IOL",
        "Kafka transporte les donnees normales, RustFS transporte le Big Data - 27 juillet 2026",
    )
    y = para(
        ax,
        "Decision centrale : api-core est le seul composant autorise a ouvrir la source. "
        "Hop et Spark recoivent un artefact verifie, jamais la connexion source.",
        6, y, 88, 10.4, weight="bold",
    )
    y = section(ax, "Deux chemins automatiques", 6, y - 2)
    y = bullets(ax, [
        "Charge normale : Source -> api-core -> Kafka (toutes les donnees) -> consumer -> Hop -> destination.",
        "Big Data : Source -> api-core -> RustFS ; Kafka transporte le manifeste ; consumer -> Spark -> destination.",
        "JDBC est serialise en JSON type, jamais en CSV.",
        "Une ligne trop grande pour Kafka bascule automatiquement vers RustFS.",
    ], 8, y, 84, 9.3)
    y = section(ax, "Ce que voit l'utilisateur", 6, y - 2)
    y = bullets(ax, [
        "La source, la destination, les colonnes, les regles Silver/Gold et l'etat du flux.",
        "Aucun choix Hop, Spark, Kafka ou RustFS dans le parcours metier.",
        "Les details techniques restent dans l'administration, les logs et le monitoring.",
    ], 8, y, 84, 9.1)
    y = note(
        ax, 6, y - 3,
        "La plateforme choisit le transport et le runtime. L'utilisateur choisit le resultat attendu.",
        88, "green",
    )
    finish(pdf, fig)


def architecture_v2_overview(pdf):
    fig, ax, y = page(pdf, "Vue generale")
    box(ax, 4, 109, 15, 11, "Utilisateur", "intention metier", "blue")
    box(ax, 25, 109, 16, 11, "api-core", "seul acces source", "green")
    box(ax, 47, 109, 16, 11, "Diagnostic", "charge et runtime", "purple")
    arrow(ax, 19, 114.5, 25, 114.5)
    arrow(ax, 41, 114.5, 47, 114.5)

    box(ax, 4, 84, 17, 11, "Source", "JDBC / API / fichier", "gray")
    arrow(ax, 21, 89.5, 29, 109)
    box(ax, 27, 73, 18, 11, "Kafka", "donnees normales", "amber")
    box(ax, 55, 73, 18, 11, "RustFS", "donnees Big Data", "purple")
    arrow(ax, 55, 109, 36, 84, "normal")
    arrow(ax, 59, 109, 64, 84, "Big Data")

    box(ax, 78, 90, 18, 12, "Kafka commande", "commande ou manifeste", "amber")
    arrow(ax, 45, 78.5, 78, 96)
    arrow(ax, 73, 78.5, 78, 96)
    box(ax, 75, 62, 21, 12, "pipeline-consumer", "ordre + SHA-256", "teal")
    arrow(ax, 87, 90, 86, 74)

    box(ax, 49, 38, 17, 11, "LOCAL", "Hop + moteur", "green")
    box(ax, 76, 38, 17, 11, "SPARK", "calcul distribue", "blue")
    arrow(ax, 82, 62, 58, 49)
    arrow(ax, 88, 62, 84, 49)
    box(ax, 58, 17, 22, 11, "Destination", "Bronze / Silver / Gold", "amber")
    arrow(ax, 58, 38, 66, 28)
    arrow(ax, 84, 38, 75, 28)

    para(
        ax,
        "Aucune fleche ne relie la source a Hop ou Spark. La frontiere d'acces source s'arrete a api-core.",
        6, 8, 90, 9.2, weight="bold",
    )
    finish(pdf, fig)


def architecture_v2_kafka(pdf):
    fig, ax, y = page(pdf, "Charge normale : toutes les donnees dans Kafka")
    box(ax, 5, 104, 17, 11, "api-core", "curseur JDBC borne", "green")
    box(ax, 29, 104, 18, 11, "Lots JSON", "500 lignes par defaut", "blue")
    box(ax, 54, 104, 17, 11, "Kafka", "meme cle / ordre", "amber")
    box(ax, 78, 104, 18, 11, "Commande", "manifest + SHA-256", "purple")
    arrow(ax, 22, 109.5, 29, 109.5)
    arrow(ax, 47, 109.5, 54, 109.5)
    arrow(ax, 71, 109.5, 78, 109.5)

    box(ax, 12, 72, 20, 12, "Consumer", "stocke chaque index", "teal")
    box(ax, 40, 72, 20, 12, "Reconstruction", "JSON Lines ordonne", "blue")
    box(ax, 68, 72, 20, 12, "Controle", "taille + SHA-256", "green")
    arrow(ax, 86, 104, 22, 84)
    arrow(ax, 32, 78, 40, 78)
    arrow(ax, 60, 78, 68, 78)

    box(ax, 27, 43, 20, 11, "Hop", "aucun secret source", "green")
    box(ax, 57, 43, 22, 11, "Destination", "Bronze / Silver / Gold", "amber")
    arrow(ax, 78, 72, 47, 54)
    arrow(ax, 47, 48.5, 57, 48.5)

    y = 29
    y = bullets(ax, [
        "Les types JSON sont conserves : null, nombre, booleen, texte et valeurs temporelles.",
        "Un doublon identique est idempotent ; un doublon different est refuse.",
        "Un lot manquant ou un hash invalide empeche seulement cette execution de demarrer.",
        "JDBC n'est jamais materialise en CSV.",
    ], 8, y, 84, 8.8)
    finish(pdf, fig)


def architecture_v2_rustfs(pdf):
    fig, ax, y = page(pdf, "Big Data : streaming multipart vers RustFS")
    box(ax, 5, 105, 17, 11, "Source JDBC", "lecture seule", "gray")
    box(ax, 29, 105, 17, 11, "api-core", "JSON Lines", "green")
    box(ax, 53, 105, 18, 11, "Partie 64 Mio", "memoire bornee", "blue")
    box(ax, 78, 105, 17, 11, "RustFS", "multipart", "purple")
    arrow(ax, 22, 110.5, 29, 110.5)
    arrow(ax, 46, 110.5, 53, 110.5)
    arrow(ax, 71, 110.5, 78, 110.5)

    box(ax, 10, 77, 20, 11, "SHA-256", "calcule a la volee", "green")
    box(ax, 40, 77, 22, 11, "Kafka", "bucket / key / taille / hash", "amber")
    box(ax, 72, 77, 22, 11, "Consumer", "telecharge et verifie", "teal")
    arrow(ax, 62, 105, 20, 88)
    arrow(ax, 87, 105, 51, 88)
    arrow(ax, 62, 82.5, 72, 82.5)

    box(ax, 38, 47, 21, 11, "Volume partage", "/tmp/iol", "gray")
    box(ax, 69, 47, 21, 11, "Spark", "artefact verifie", "blue")
    arrow(ax, 83, 77, 49, 58)
    arrow(ax, 59, 52.5, 69, 52.5)

    y = 33
    y = bullets(ax, [
        "Pas de fichier JDBC complet sur le disque de api-core.",
        "Le multipart est annule en cas d'erreur avant finalisation.",
        "Kafka ne transporte que le manifeste lorsque la charge est Big Data.",
        "Succes : statut terminal, ACK Kafka, puis suppression immediate de l'objet RustFS.",
        "Echec : conservation 72 h maximum, puis purge planifiee toutes les heures.",
    ], 8, y, 84, 8.8)
    finish(pdf, fig)


def architecture_v2_security(pdf):
    fig, ax, y = page(pdf, "Barriere de securite avant Hop et Spark")
    box(ax, 6, 101, 23, 13, "Dans api-core", "requete + URL + credentials source", "red")
    box(ax, 38, 101, 23, 13, "Dans Kafka", "donnees ou manifeste + cible", "amber")
    box(ax, 70, 101, 23, 13, "Dans le moteur", "artefact + credentials cible", "green")
    arrow(ax, 29, 107.5, 38, 107.5, "nettoyage")
    arrow(ax, 61, 107.5, 70, 107.5, "controle")

    y = 84
    y = section(ax, "Le consumer refuse l'execution si", 6, y)
    y = bullets(ax, [
        "une source non-PUSH n'a pas ete reconstruite depuis Kafka ou RustFS ;",
        "le protocole courant est POSTGRES, MYSQL, ORACLE, MSSQL, SQLITE ou un autre JDBC ;",
        "source_connection, source_connection_id, query, username ou password source subsiste ;",
        "la taille ou le SHA-256 ne correspond pas au manifeste.",
    ], 8, y, 84, 9.0)
    y = section(ax, "Ce qui reste autorise", 6, y - 2)
    y = para(
        ax,
        "target_connection reste presente : Hop ou Spark doit ecrire dans la destination. "
        "Cette connexion cible n'autorise pas un retour vers la source.",
        6, y, 90, 9.2,
    )
    y = note(
        ax, 6, y - 3,
        "Les moteurs contiennent aussi une defense en profondeur : un protocole JDBC source provoque une erreur explicite.",
        88, "red",
    )
    finish(pdf, fig)


def architecture_v2_decision(pdf):
    fig, ax, y = page(pdf, "Decision automatique et experience utilisateur")
    box(ax, 6, 106, 20, 11, "Demande", "source + resultat", "blue")
    box(ax, 39, 106, 20, 11, "Diagnostic", "COUNT / taille / borne API", "green")
    box(ax, 72, 106, 20, 11, "Decision", "LOCAL ou SPARK", "purple")
    arrow(ax, 26, 111.5, 39, 111.5)
    arrow(ax, 59, 111.5, 72, 111.5)

    box(ax, 14, 76, 25, 12, "Sous le seuil", "Kafka + LOCAL", "amber")
    box(ax, 61, 76, 25, 12, "Au-dessus / inconnu", "RustFS + SPARK", "blue")
    arrow(ax, 82, 106, 27, 88)
    arrow(ax, 82, 106, 73, 88)

    y = 62
    y = bullets(ax, [
        "Seuil JDBC de production : 10 000 000 lignes.",
        "Seuil fichier de production : 2 Gio.",
        "Un diagnostic JDBC incertain choisit SPARK par prudence.",
        "Un gros fichier peut declencher SPARK selon sa taille.",
        "Le choix manuel transport_mode ne force plus RustFS sur une petite charge.",
        "loadAssessment conserve la raison de la decision pour l'audit.",
    ], 8, y, 84, 9.0)
    y = section(ax, "Regle UX", 6, y - 2)
    para(
        ax,
        "L'utilisateur metier ne choisit aucun composant technique. Les termes Hop, Spark, Kafka et RustFS "
        "restent dans les vues admin, l'exploitation et les logs.",
        6, y, 90, 9.4, weight="bold",
    )
    finish(pdf, fig)


def architecture_v2_code(pdf):
    fig, ax, y = page(pdf, "Ancrages dans le code et controles production")
    y = section(ax, "Code", 6, y)
    y = bullets(ax, [
        "SourceLoadEstimatorService.java : diagnostic automatique de charge.",
        "KafkaPipelineEventService.java : choix final LOCAL/SPARK.",
        "SourceDataTransportService.java : JDBC vers Kafka JSON ou RustFS.",
        "ObjectStorageService.java : multipart streaming et SHA-256.",
        "KafkaDataChunkStore.java : ordre, doublons et controle d'integrite.",
        "PipelineOrchestrator.java : barriere anti-acces source.",
        "moteur_universel.py et spark_etl.py : JDBC source explicitement interdit.",
    ], 8, y, 84, 8.8)
    y = section(ax, "Production", 6, y - 2)
    y = bullets(ax, [
        "Activer TLS/SASL et les ACL Kafka.",
        "Utiliser des credentials RustFS uniques et une rotation des secrets.",
        "Limiter le compte source a SELECT et le compte cible aux schemas requis.",
        "Configurer la retention Kafka et le cycle de vie RustFS.",
        "Superviser les erreurs de hash, lots manquants, multipart abandonnes et espace /tmp/iol.",
        "Calibrer les seuils avec les ressources et les volumes reels.",
    ], 8, y, 84, 8.8)
    y = note(
        ax, 6, y - 2,
        "Resultat : le transport est coherent, observable et independant du moteur de calcul choisi.",
        88, "teal",
    )
    finish(pdf, fig)


def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with PdfPages(OUT) as pdf:
        architecture_v2_title(pdf)
        architecture_v2_overview(pdf)
        architecture_v2_kafka(pdf)
        architecture_v2_rustfs(pdf)
        architecture_v2_security(pdf)
        architecture_v2_decision(pdf)
        medallion(pdf)
        architecture_v2_code(pdf)
    print(OUT)


if __name__ == "__main__":
    main()
