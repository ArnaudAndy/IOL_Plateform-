package com.iol.etlplatform.sourcegateway.readmodel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detecte la derive entre le read-model du gateway et l'entite de reference
 * d'api-core.
 *
 * Pourquoi ce test existe: Spring Data MongoDB ignore silencieusement les champs
 * inconnus. Un champ RENOMME cote api-core ne provoque donc aucune erreur — il
 * devient simplement nul cote gateway, et la commande publiee perd une
 * information sans que rien ne le signale. Le bug n'apparaitrait qu'a
 * l'execution, sur les donnees d'un client.
 *
 * Le test compare les noms de champs par analyse du source, sans dependance a la
 * compilation d'api-core. Si le depot d'api-core n'est pas disponible (image de
 * build isolee), le test est ignore plutot qu'echoue: il ne doit pas bloquer une
 * construction legitime du seul module gateway.
 */
class ReadModelParityTest {

    private static final Pattern FIELD =
            Pattern.compile("^\\s*private\\s+[\\w<>,\\[\\]\\s.]+?\\s+(\\w+)\\s*(?:=|;)", Pattern.MULTILINE);

    /** Champs delibérément absents du read-model, avec leur justification. */
    private static final Set<String> NON_LUS = Set.of(
            "description",       // presentation seulement
            "standardDomain",    // deprecie, usage AiService dans api-core
            "metadataFileName",  // metadonnees d'upload, non transportees
            "metadataJson",
            "metadataVersion",
            "aggregationScripts", // legacy, remplace par goldConfigGlobal
            "createdAt",
            "updatedAt"
    );

    @Test
    void leReadModelNeDoitPasAvoirRateUnChampDeLEntiteDeReference() throws IOException {
        Path reference = Path.of("..", "api-core", "src", "main", "java", "com", "iol",
                "etlplatform", "entity", "WorkflowConfig.java");
        Assumptions.assumeTrue(Files.exists(reference),
                "api-core absent de ce contexte de build: parite non verifiable");

        Set<String> attendus = new TreeSet<>(champs(Files.readString(reference)));
        attendus.removeAll(NON_LUS);

        Set<String> presents = champs(Files.readString(
                Path.of("src", "main", "java", "com", "iol", "etlplatform",
                        "sourcegateway", "readmodel", "WorkflowConfig.java")));

        Set<String> manquants = new TreeSet<>(attendus);
        manquants.removeAll(presents);

        assertTrue(manquants.isEmpty(),
                "Champs presents dans l'entite api-core mais absents du read-model: " + manquants
                        + ". Un champ renomme ou ajoute cote api-core devient silencieusement nul ici. "
                        + "Ajoutez-le au read-model, ou declarez-le dans NON_LUS avec sa justification.");
    }

    private static Set<String> champs(String source) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = FIELD.matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
