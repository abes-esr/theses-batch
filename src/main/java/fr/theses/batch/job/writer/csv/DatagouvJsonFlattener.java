package fr.theses.batch.job.writer.csv;

import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aplatit un document JSON en couples chemin / valeur pour l'export CSV :
 * un attribut d'objet devient objet.attribut, un élément de tableau devient tableau.index
 * (ex : directeurs_these.0.nom, titres.fr, sujets_rameau.12).
 * <p>
 * Les valeurs null, les objets vides et les tableaux vides ne produisent aucune colonne :
 * la cellule correspondante reste vide dans le CSV.
 */
public final class DatagouvJsonFlattener {

    private DatagouvJsonFlattener() {
    }

    /**
     * @param node Document JSON à aplatir (en pratique, une thèse du JSONL).
     * @return Les valeurs indexées par leur chemin, dans l'ordre du document.
     */
    public static Map<String, String> flatten(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        flatten("", node, values);
        return values;
    }

    private static void flatten(String path, JsonNode node, Map<String, String> values) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                flatten(child(path, property.getKey()), property.getValue(), values);
            }
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode element : node.values()) {
                flatten(child(path, String.valueOf(index++)), element, values);
            }
        } else {
            values.put(path, node.asString());
        }
    }

    private static String child(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }
}
