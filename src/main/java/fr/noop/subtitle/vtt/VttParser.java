package fr.noop.subtitle.vtt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.noop.subtitle.model.SubtitleLine;
import fr.noop.subtitle.model.SubtitleParser;
import fr.noop.subtitle.model.SubtitleParsingException;
import fr.noop.subtitle.util.*;

public class VttParser implements SubtitleParser {

    private enum CursorStatus {
        NONE,
        SIGNATURE,
        EMPTY_LINE,
        CUE_ID,
        CUE_TIMECODE,
        CUE_TEXT;
    }

    private String charset; // Charset of the input files
    private Map<String, SubtitleStyle> globalStyles = new HashMap<>();

    public VttParser(String charset) {
        this.charset = charset;
    }

    @Override
    public VttObject parse(InputStream is) throws IOException, SubtitleParsingException {
        return parse(is, true);
    }

    @Override
    public VttObject parse(InputStream is, boolean strict) throws IOException, SubtitleParsingException {
        VttObject vttObject = new VttObject();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, this.charset));
        String textLine;
        CursorStatus cursorStatus = CursorStatus.NONE;
        VttCue cue = null;
        String cueText = "";
        int lineNumber = 0;

        var lineHeightPercent = "";
        var lineAlign = "center";
        while ((textLine = br.readLine()) != null) {
            lineNumber++;
            textLine = textLine.trim();

            // Remove BOM
            if (cursorStatus == CursorStatus.NONE) {
                textLine = StringUtils.removeBOM(textLine);
            }

            // All VTT files start with WEBVTT
            if (cursorStatus == CursorStatus.NONE && textLine.equals("WEBVTT")) {
                cursorStatus = CursorStatus.SIGNATURE;
                continue;
            }

            // Handle multiple STYLE blocks
            if (cursorStatus == CursorStatus.SIGNATURE && textLine.equals("STYLE")) {
                StringBuilder styleBlock = new StringBuilder();
                while ((textLine = br.readLine()) != null && !textLine.trim().isEmpty()) {
                    lineNumber++;
                    styleBlock.append(textLine).append("\n");
                }
                parseStyleBlock(styleBlock.toString());
                continue;
            }

            // Skip empty lines
            if (cursorStatus == CursorStatus.SIGNATURE || cursorStatus == CursorStatus.EMPTY_LINE) {
                if (textLine.isEmpty()) {
                    continue;
                }

                // Start of a new cue
                cue = new VttCue();
                cursorStatus = CursorStatus.CUE_ID;

                // Check if the line is not a timecode but a cue ID
                if (textLine.length() < 16 || !textLine.substring(13, 16).equals("-->")) {
                    cue.setId(textLine);
                    continue;
                }
            }

            // Process timecode line
            if (cursorStatus == CursorStatus.CUE_ID) {
                lineAlign = "center";
                lineHeightPercent = "";

                Pattern timecodePattern = Pattern.compile(
                        "(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) --> (\\d{2}:\\d{2}:\\d{2}\\.\\d{3})(.*)"
                );
                Matcher matcher = timecodePattern.matcher(textLine);
                if (!matcher.matches()) {
                    throw new SubtitleParsingException(String.format(
                            "Timecode line is badly formatted: %s at line %d", textLine, lineNumber));
                }

                // Extraction des timecodes
                cue.setStartTime(this.parseTimeCode(matcher.group(1)));
                cue.setEndTime(this.parseTimeCode(matcher.group(2)));

                // Extraction des attributs supplémentaires (line, align, etc.)
                String attributes = matcher.group(3);
                if (attributes != null && !attributes.isBlank()) {
                    Pattern attributePattern = Pattern.compile("(\\w+):([^ ]+)");
                    Matcher attrMatcher = attributePattern.matcher(attributes);
                    while (attrMatcher.find()) {
                        String key = attrMatcher.group(1).trim();
                        String value = attrMatcher.group(2).trim();

                        switch (key) {
                            case "line":
                                lineHeightPercent = value;
                                break;
                            case "align":
                                lineAlign = value;
                                break;
                            default:
                                // Ignorer les attributs non gérés
                                break;
                        }
                    }
                }

                cursorStatus = CursorStatus.CUE_TIMECODE;
                continue;
            }


            // Handle empty cue text in strict mode
            if (cursorStatus == CursorStatus.CUE_TIMECODE && textLine.isEmpty() && strict) {
                throw new SubtitleParsingException(String.format(
                        "Empty subtitle is not allowed in WebVTT for cue at timecode: %s at line %d",
                        cue.getStartTime(), lineNumber));
            }

            // End of a cue block
            if ((cursorStatus == CursorStatus.CUE_TIMECODE || cursorStatus == CursorStatus.CUE_TEXT) && textLine.isEmpty()) {
                cue.setLines(parseCueText(cueText, lineAlign, lineHeightPercent, lineNumber));
                vttObject.addCue(cue);
                cue = null;
                cueText = "";
                cursorStatus = CursorStatus.EMPTY_LINE;
                continue;
            }

            // Add text to the cue
            if (cursorStatus == CursorStatus.CUE_TIMECODE || cursorStatus == CursorStatus.CUE_TEXT) {
                if (!cueText.isEmpty()) {
                    cueText += "\n";
                }

                cueText += textLine;
                cursorStatus = CursorStatus.CUE_TEXT;
                continue;
            }

            // Handle unexpected lines
            throw new SubtitleParsingException(String.format("Unexpected line: %s at line %d", textLine, lineNumber));
        }

        return vttObject;
    }

    private void parseStyleBlock(String styleContent) {
        String[] rules = styleContent.split("}");
        for (String rule : rules) {
            if (rule.contains("::cue(")) {
                int classStart = rule.indexOf('.') + 1;
                int classEnd = rule.indexOf(')', classStart);
                if (classStart > 0 && classEnd > classStart) {
                    String className = rule.substring(classStart, classEnd).trim();

                    SubtitleStyle style = new SubtitleStyle();
                    int braceStart = rule.indexOf('{') + 1;
                    String properties = rule.substring(braceStart).trim();

                    for (String property : properties.split(";")) {
                        if (property.contains(":")) {
                            String[] keyValue = property.split(":");
                            String key = keyValue[0].trim();
                            String value = keyValue[1].trim();

                            SubtitleStyle.Property mappedProperty = mapCssProperty(key);
                            if (mappedProperty != null) {
                                style.setProperty(mappedProperty, value);
                            }
                        }
                    }

                    globalStyles.put(className, style);
                }
            }
        }
    }

    private List<SubtitleLine> parseCueText(String cueText, String lineAlign, String lineHeightPercent, int lineNumber) throws SubtitleParsingException {
        List<SubtitleLine> cueLines = new ArrayList<>();
        List<String> openTags = new ArrayList<>();
        VttLine currentLine = new VttLine();

        // Expression régulière pour détecter les balises ouvrantes et fermantes
        String regex = "<(/?[a-zA-Z0-9_.\\-]+)>";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(cueText);

        int lastIndex = 0; // Position pour suivre le texte entre les balises
        for (int i = 0; i < cueText.length(); i++) {
            if (cueText.charAt(i) == '\n' || i == cueText.length() - 1) {
                // Traiter la ligne jusqu'à ici
                String lineText = cueText.substring(lastIndex, i == cueText.length() - 1 ? i + 1 : i).trim();
                if (!lineText.isEmpty()) {
                    Matcher lineMatcher = pattern.matcher(lineText);
                    int lineLastIndex = 0;
                    while (lineMatcher.find()) {
                        // Ajouter le texte avant la balise détectée
                        if (lineMatcher.start() > lineLastIndex) {
                            String plainText = lineText.substring(lineLastIndex, lineMatcher.start());
                            if (!plainText.isBlank()) {
                                addTextToCueLine(currentLine, plainText, lineAlign, lineHeightPercent, new ArrayList<>(openTags));
                            }
                        }

                        String tag = lineMatcher.group(1); // Contenu de la balise
                        if (!tag.startsWith("/")) {
                            // Balise ouvrante
                            openTags.add(tag);
                        } else {
                            // Balise fermante
                            if (!openTags.isEmpty()) {
                                openTags.remove(openTags.size() - 1);
                            } else {
                                throw new SubtitleParsingException(String.format(
                                        "Mismatched closing tag: %s at line %d", tag, lineNumber));
                            }
                        }

                        lineLastIndex = lineMatcher.end(); // Avancer après la balise
                    }

                    // Ajouter le texte restant après la dernière balise
                    if (lineLastIndex < lineText.length()) {
                        String plainText = lineText.substring(lineLastIndex);
                        if (!plainText.isBlank()) {
                            addTextToCueLine(currentLine, plainText, lineAlign, lineHeightPercent, new ArrayList<>(openTags));
                        }
                    }
                }

                if (!currentLine.isEmpty()) {
                    cueLines.add(currentLine);
                    currentLine = new VttLine();
                }
                lastIndex = i + 1; // Avancer au caractère suivant après '\n'
            }
        }

        // Vérifier les balises ouvertes restantes
        if (!openTags.isEmpty()) {
            throw new SubtitleParsingException(String.format(
                    "Unclosed tag(s) at the end of the cue: %s at line %d", openTags, lineNumber));
        }

        return cueLines;
    }

    private void addTextToCueLine(VttLine cueLine, String text, String lineAlign, String lineHeightPercent, List<String> activeTags) {
        if (activeTags.isEmpty()) {
            cueLine.addText(new SubtitlePlainText(text));
        } else {
            SubtitleStyle style = new SubtitleStyle();
            for (String tag : activeTags) {
                if (tag.startsWith("c.")) {
                    String classList = tag.substring(2); // Extraire la partie après "c."
                    String[] classes = classList.split("\\."); // Diviser par les points
                    for (String className : classes) {
                        SubtitleStyle globalStyle = globalStyles.get(className);
                        if (globalStyle != null) {
                            style.merge(globalStyle); // Fusionner les styles pour chaque classe
                        }
                    }
                }
            }
            if (lineAlign != null && !lineAlign.isBlank()) {
                switch (lineAlign.toLowerCase()) {
                    case "start":
                    case "left":
                        style.setProperty(SubtitleStyle.Property.TEXT_ALIGN, SubtitleStyle.TextAlign.LEFT);
                        break;
                    case "right":
                    case "end":
                        style.setProperty(SubtitleStyle.Property.TEXT_ALIGN, SubtitleStyle.TextAlign.RIGHT);
                        break;
                    default:
                        style.setProperty(SubtitleStyle.Property.TEXT_ALIGN, SubtitleStyle.TextAlign.CENTER);
                }
            }
            if (lineHeightPercent != null && !lineHeightPercent.isBlank()) {
                style.setProperty(SubtitleStyle.Property.VERTICAL_POSITION_PERCENT, lineHeightPercent);
            }

            if (style.hasProperties()) {
                cueLine.addText(new SubtitleStyledText(text, style));
            } else {
                cueLine.addText(new SubtitlePlainText(text));
            }
        }
    }

    private SubtitleStyle.Property mapCssProperty(String cssKey) {
        switch (cssKey.toLowerCase()) {
            case "direction":
                return SubtitleStyle.Property.DIRECTION;
            case "text-align":
                return SubtitleStyle.Property.TEXT_ALIGN;
            case "background-color":
                return SubtitleStyle.Property.BACKGROUND_COLOR;
            case "color":
                return SubtitleStyle.Property.COLOR;
            case "font-family":
                return SubtitleStyle.Property.FONT_FAMILY;
            case "font-style":
                return SubtitleStyle.Property.FONT_STYLE;
            case "font-weight":
                return SubtitleStyle.Property.FONT_WEIGHT;
            case "text-decoration":
                return SubtitleStyle.Property.TEXT_DECORATION;
            default:
                return null;
        }
    }

    private SubtitleTimeCode parseTimeCode(String timeCodeString) throws SubtitleParsingException {
        try {
            int hour = Integer.parseInt(timeCodeString.substring(0, 2));
            int minute = Integer.parseInt(timeCodeString.substring(3, 5));
            int second = Integer.parseInt(timeCodeString.substring(6, 8));
            int millisecond = Integer.parseInt(timeCodeString.substring(9, 12));
            return new SubtitleTimeCode(hour, minute, second, millisecond);
        } catch (NumberFormatException e) {
            throw new SubtitleParsingException(String.format(
                    "Unable to parse time code: %s", timeCodeString));
        }
    }
}
