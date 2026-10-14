package fr.theses.batch.util.log;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/**
 * Filtre Log4j2 à placer sur les loggers PDFBox / FontBox (voir log4j2-spring.xml).
 * <p>
 * Pendant le traitement d'un PDF, les événements sont comptés par {@link PdfWarningCollector}
 * et écartés du log ; le processor écrit ensuite un récapitulatif par fichier.
 * En dehors d'un traitement, ils passent tels quels.
 */
@Plugin(name = "PdfWarningFilter", category = Node.CATEGORY, elementType = Filter.ELEMENT_TYPE)
public final class PdfWarningFilter extends AbstractFilter {

    private PdfWarningFilter() {
        super(Result.DENY, Result.NEUTRAL);
    }

    @PluginFactory
    public static PdfWarningFilter createFilter() {
        return new PdfWarningFilter();
    }

    @Override
    public Result filter(LogEvent event) {
        return PdfWarningCollector.collect(event) ? onMatch : onMismatch;
    }
}
