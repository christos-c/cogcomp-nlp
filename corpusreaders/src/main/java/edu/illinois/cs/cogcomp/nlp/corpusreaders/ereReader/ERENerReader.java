package edu.illinois.cs.cogcomp.nlp.corpusreaders.ereReader;

import edu.illinois.cs.cogcomp.core.datastructures.ViewNames;
import edu.illinois.cs.cogcomp.core.datastructures.textannotation.Constituent;
import edu.illinois.cs.cogcomp.core.datastructures.textannotation.SpanLabelView;
import edu.illinois.cs.cogcomp.core.datastructures.textannotation.TextAnnotation;
import edu.illinois.cs.cogcomp.nlp.corpusreaders.aceReader.SimpleXMLParser;
import edu.illinois.cs.cogcomp.nlp.corpusreaders.aceReader.XMLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import org.w3c.dom.Node;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Reads ERE data and instantiates TextAnnotations with the corresponding NER view.
 * Also provides functionality to support combination with readers of other ERE annotations from the same source.
 *
 * ERE annotations are provided in stand-off form: each source file (in xml, and from which character offsets
 *     are computed) has one or more corresponding annotation files (also in xml). Each annotation file corresponds
 *     to a span of the source file, and contains all information about entities, relations, and events for that
 *     span.  Entity and event identifiers presumably carry across spans from the same document.
 *
 * This reader allows the user to generate either a mention view or an NER view.  NERs can be identified in a
 *     mention view via its type attribute.
 *
 * TODO: ascertain whether NER mentions can overlap. Probably not.
 *
 * This code is extracted from Tom Redman's code for generating CoNLL-format ERE NER data.
 * TODO: allow non-token-level annotations (i.e. subtokens)
 * @author mssammon
 */
public class ERENerReader extends EREDocumentReader {

    /**
     * tags in ERE markup filess
     */
    public static final String ENTITIES = "entities";
    public static final String ENTITY = "entity";
    public static final String OFFSET = "offset";
    public static final String TYPE = "type";
    public static final String ENTITY_MENTION = "entity_mention";
    public static final String NOUN_TYPE = "noun_type";
    public static final String PRO = "PRO";
    public static final String NOM = "NOM";
    public static final String NAM = "NAM";
    public static final String LENGTH = "length";
    public static final String MENTION_TEXT = "mention_text";
    private static final String NAME = EREDocumentReader.class.getCanonicalName();
    private static final Logger logger = LoggerFactory.getLogger(ERENerReader.class);
    private final boolean addNominalMentions;
    private final String viewName;

    private int numOverlaps = 0;
    private int numOffsetErrors = 0;
    private int numConstituent = 0;


    private int starts [];
    private int ends [];
    /**
     * ERE annotation offsets appear to include some errors, such as including a leading space
     */
    private boolean allowOffsetSlack;

    /**
     * ERE annotations allow for sub-word annotation.
     */
    private boolean allowSubwordOffsets;

    /**
     * @param corpusName      the name of the corpus, this can be anything.
     * @param sourceDirectory the name of the directory containing the file.
     * @throws Exception
     */
    public ERENerReader(String corpusName, String sourceDirectory, boolean addNominalMentions) throws Exception {
        super(corpusName, sourceDirectory);
        this.addNominalMentions = addNominalMentions;
        this.viewName = addNominalMentions ? ViewNames.MENTION_ERE : ViewNames.NER_ERE;
        allowOffsetSlack = true;
        allowSubwordOffsets = true;
    }

    @Override
    public List<TextAnnotation> getTextAnnotationsFromFile(List<Path> corpusFileListEntry) throws Exception {

        TextAnnotation sourceTa = super.getTextAnnotationsFromFile(corpusFileListEntry).get(0);
        SpanLabelView tokens = (SpanLabelView) sourceTa.getView(ViewNames.TOKENS);
        compileOffsets(tokens);
        SpanLabelView nerView = new SpanLabelView(getViewName(), NAME, sourceTa, 1.0, false);

        // now pull all mentions we deal with. Start from file list index 1, as index 0 was source text
        for (int i = 1; i < corpusFileListEntry.size(); ++i) {
            Document doc = SimpleXMLParser.getDocument(corpusFileListEntry.get(i).toFile());
            getEntitiesFromFile(doc, nerView);
        }
        sourceTa.addView(getViewName(), nerView);

        logger.info("number of constituents created: {}", numConstituent );
        logger.info("number of overlaps preventing creation: {}", numOverlaps );
        logger.info("number of missed offsets (annotation error): {}", numOffsetErrors );

        return Collections.singletonList(sourceTa);
    }

    private void getEntitiesFromFile(Document doc, SpanLabelView nerView) throws XMLException {
        Element element = doc.getDocumentElement();
        Element entityElement = SimpleXMLParser.getElement(element, ENTITIES);
        NodeList entityNL = entityElement.getElementsByTagName(ENTITY);
        for (int j = 0; j < entityNL.getLength(); ++j) {

            readEntity(entityNL.item(j), nerView);
        }
    }


    /**
     * get the start and end offsets of all constituents and store them
     * @param tokens SpanLabelView containing Token info (from TextAnnotation)
     */
    private void compileOffsets(SpanLabelView tokens) {
        List<Constituent> constituents = tokens.getConstituents();
        int n = constituents.size();
        starts = new int[n];
        ends = new int[n];
        int i = 0;
        for (Constituent cons : tokens.getConstituents()) {
            starts[i] = cons.getStartCharOffset();
            ends[i] = cons.getEndCharOffset();
            i++;
        }
    }

    /**
     * Find the index of the first constituent at startOffset.
     * @param startOffset the character offset we want.
     * @return the index of the first constituent.
     */
    private int findStartIndex(int startOffset) {
        for (int i = 0 ; i < starts.length; i++) {
            if (startOffset == starts[i])
                return i;
            if (startOffset < starts[i]) {
                if (allowOffsetSlack)
                    if (startOffset == starts[i]-1)
                        return i;
                throw new RuntimeException("Index "+startOffset+" was not exact.");
            }
        }
        throw new RuntimeException("Index "+startOffset+" was out of range.");
    }

    /**
     * Find the index of the first constituent *near* startOffset.
     * @param startOffset the character offset we want.
     * @return the index of the first constituent.
     */
    private int findStartIndexIgnoreError(int startOffset) {
        for (int i = 0 ; i < starts.length; i++) {
            if (startOffset <= starts[i])
                return i;
        }
        throw new RuntimeException("Index "+startOffset+" was out of range.");
    }

    /**
     * Find the index of the first constituent at startOffset.
     * @param endOffset the character offset we want.
     * @return the index of the first constituent.
     */
    private int findEndIndex(int endOffset, String rawText) {
        int prevOffset = 0;
        for (int i = 0 ; i < ends.length; i++) {
            if (endOffset == ends[i])
                return i;
            if (endOffset < ends[i]) {
                if (allowSubwordOffsets && endOffset == ends[i]-1)
                    return i;
                else if (allowOffsetSlack && endOffset == prevOffset + 1 && rawText.substring(prevOffset, prevOffset+1).matches("\\s+"))
                    return i-1;
                throw new RuntimeException("End Index "+endOffset+" was not exact.");
            }
            prevOffset = ends[i];
        }
        throw new RuntimeException("Index "+endOffset+" was out of range.");
    }

    /**
     * Find the index of the first constituent at startOffset.
     * @param endOffset the character offset we want.
     * @return the index of the first constituent.
     */
    private int findEndIndexIgnoreError(int endOffset) {
        for (int i = 0 ; i < ends.length; i++) {
            if (endOffset <= ends[i])
                if (i > 0 && Math.abs(endOffset-ends[i]) > Math.abs(endOffset - ends[i-1]))
                    return i-1;
                else
                    return i;
        }
        throw new RuntimeException("Index "+endOffset+" was out of range.");
    }

    /**
     * read the entities form the gold standard xml and produce appropriate constituents in the view.
     * NOTE: the constituents will not be ordered when we are done.
     * @param node the entity node, contains the more specific mentions of that entity.
     * @param view the span label view we will add the labels to.
     * @throws XMLException
     */
    public void readEntity(Node node, SpanLabelView view) throws XMLException {
        NamedNodeMap nnMap = node.getAttributes();
        String label = nnMap.getNamedItem(TYPE).getNodeValue();

        // now for specifics get the mentions.
        NodeList nl = ((Element)node).getElementsByTagName(ENTITY_MENTION);
        TextAnnotation ta = view.getTextAnnotation();
        String rawText = ta.getText();


        for (int i = 0; i < nl.getLength(); ++i) {
            Node mentionNode = nl.item(i);
            nnMap = mentionNode.getAttributes();
            String noun_type = nnMap.getNamedItem(NOUN_TYPE).getNodeValue();

            if (noun_type.equals(PRO) || noun_type.equals(NOM)) {
                if (!addNominalMentions)
                    continue;
            }



            // we have a valid mention(a "NAM" or a "NOM"), add it to out view.
            int offset = Integer.parseInt(nnMap.getNamedItem(OFFSET).getNodeValue());
            int length = Integer.parseInt(nnMap.getNamedItem(LENGTH).getNodeValue());

            NodeList mnl = ((Element)mentionNode).getElementsByTagName(MENTION_TEXT);
            String text = null;
            if (mnl.getLength() > 0) {
                text = SimpleXMLParser.getContentString((Element) mnl.item(0));
            }
            int si=0, ei=0;
            try {
                si = findStartIndex(offset);
                ei = findEndIndex(offset+length, rawText);
            } catch (IllegalArgumentException iae) {
                List<Constituent> foo = view.getConstituentsCoveringSpan(si, ei);
                logger.error("Constituents covered existing span : "+foo.get(0));
                System.exit(1);
            } catch (RuntimeException re) {
                numOffsetErrors++;
                String rawStr = ta.getText().substring(offset, offset+length);
                logger.error("Error finding text for '{}':", rawStr);
                boolean siwaszero = false;
                if (si == 0) {siwaszero = true;}
                si = findStartIndexIgnoreError(offset);
                ei = findEndIndexIgnoreError(offset+length);
                if (siwaszero)
                    logger.error("Could not find start token : text='"+text+"' at "+offset+" to "+ (offset + length));
                else
                    logger.error("Could not find end token : text='"+text+"' at "+offset+" to "+ (offset + length));
                int max = ta.getTokens().length;
                int start = si >= 2 ? si - 2 : 0;
                int end = (ei+2) < max ? ei+2 : max;
                StringBuilder bldr = new StringBuilder();
                for (int jj = start; jj < end; jj++) {
                    bldr.append(" ");
                    if (jj == si)
                        bldr.append(":");
                    bldr.append(ta.getToken(jj));
                    if (jj == ei)
                        bldr.append(":");
                    bldr.append(" ");
                }
                bldr.append("\n");
                logger.error(bldr.toString());
            }
            try {
                Constituent c = new Constituent(label, getViewName(), ta, si, ei+1);
                c.addAttribute(EREDocumentReader.EntityMentionTypeAttribute, noun_type );
                view.addConstituent(c);
                numConstituent++;

            } catch (IllegalArgumentException iae) {
                numOverlaps++;
            }
        }

    }

    public String getViewName() {
        return viewName;
    }
}
