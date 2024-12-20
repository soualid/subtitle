package fr.noop.subtitle.stl;

import fr.noop.charset.iso6937.Iso6937Charset;
import fr.noop.subtitle.base.BaseSubtitleCue;
import fr.noop.subtitle.model.*;
import fr.noop.subtitle.util.SubtitleStyle;
import fr.noop.subtitle.util.SubtitleStyledText;
import fr.noop.subtitle.util.SubtitleTimeCode;
import fr.noop.subtitle.vtt.VttCue;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Corrected StlWriter for generating valid STL files with color and vertical position support.
 */
public class StlWriter implements SubtitleWriter {

    private static final int HEADER_SIZE = 1024; // Taille de l'en-tête dans un fichier STL
    private static final int LINE_LENGTH = 37;  // Nombre de caractères max par ligne dans le format STL
    private static final int TTI_BLOCK_SIZE = 128; // Taille d'un bloc TTI
    private static final int MAX_LINES = 10;    // Nombre maximum de lignes gérées

    @Override
    public void write(SubtitleObject subtitleObject, OutputStream os) {
        try (DataOutputStream dos = new DataOutputStream(os)) {
            writeHeader(subtitleObject, dos);
            writeBody(subtitleObject, dos);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write STL file", e);
        }
    }

    private void writeHeader(SubtitleObject subtitleObject, DataOutputStream dos) throws IOException {
        byte[] header = new byte[HEADER_SIZE];

        // Code Page Number (CPN) - Position 0
        header[0] = (byte) 0x38; // '8' (ASCII 56)
        header[1] = (byte) 0x35; // '5' (ASCII 53)
        header[2] = (byte) 0x30; // '0' (ASCII 48)

        // Disk Format Code (DFC) - Position 3
        System.arraycopy("STL25.01".getBytes(StandardCharsets.US_ASCII), 0, header, 3, 8);

        // Display Standard Code (DSC) - Position 11
        header[11] = (byte) 0x32; // Level 2 Teletext

        // Character Code Table (CCT) - Position 12
        header[12] = (byte) 0x30; // Latin alphabet (0)
        header[13] = (byte) 0x30; // Latin alphabet (0)

        // Language Code (LC) - Position 13-14
        String languageCode = (String) subtitleObject.getProperty(SubtitleObject.Property.LANGUAGE);
        if (languageCode != null && languageCode.length() == 2) {
            System.arraycopy(languageCode.getBytes(StandardCharsets.US_ASCII), 0, header, 13, 2);
        } else {
            System.arraycopy("0F".getBytes(StandardCharsets.US_ASCII), 0, header, 13, 2); // Default: '0F' (French)
        }

        // Original Program Title (OPT) - Position 15-47
        Arrays.fill(header, 15, 48, (byte) 0x20);
        String title = (String) subtitleObject.getProperty(SubtitleObject.Property.TITLE);
        if (StringUtils.isNotBlank(title)) {
            byte[] titleBytes = title.getBytes(StandardCharsets.ISO_8859_1);
            int length = Math.min(titleBytes.length, 33);
            System.arraycopy(titleBytes, 0, header, 15, length);
        }

        // 48..79 32 Original Episode Title
        Arrays.fill(header, 48, 80, (byte) 0x20);
        //System.arraycopy("                                  ".getBytes(), 0, header, 47, 33);

        // 80..111 32 Translated Programme Title TPT
        Arrays.fill(header, 80, 112, (byte) 0x20);
        //System.arraycopy("                                 ".getBytes(), 0, header, 79, 33);

        // 112..143 32 Translated Episode Title TET
        Arrays.fill(header, 112, 144, (byte) 0x20);
        //System.arraycopy("                                 ".getBytes(), 0, header, 111, 33);

        // 144..175 32 Translator's Name TN
        Arrays.fill(header, 144, 176, (byte) 0x20);
        //System.arraycopy("                                 ".getBytes(), 0, header, 143, 33);

        // 176..207 32 Translator's Contact Details TCD
        Arrays.fill(header, 176, 208, (byte) 0x20);
        //System.arraycopy("                                 ".getBytes(), 0, header, 175, 33);

        // 208..223 16 Subtitle List Reference Code SLR
        Arrays.fill(header, 208, 224, (byte) 0x20);
        //System.arraycopy("                ".getBytes(), 0, header, 208, 16);

        // Creation Date (CD) - Position 224-229
        var df = new SimpleDateFormat("yyMMdd").format(System.currentTimeMillis());
        System.arraycopy(df.getBytes(), 0, header, 224, 6);

        // Revision Date (RD) - Position 230-235
        System.arraycopy(df.getBytes(), 0, header, 230, 6);


        // 236..237 2 Revision number RN
        System.arraycopy( "01".getBytes(), 0, header, 236, 2);

        // Total Number of Text and Timing Information (TTI) blocks - Position 238-242
        var ttiCount = StringUtils.leftPad(""+subtitleObject.getCues().size(), 6, "0");
        if (subtitleObject instanceof StlObject) {
            var stlSubtitleObject = (StlObject) subtitleObject;
            ttiCount = StringUtils.leftPad(""+stlSubtitleObject.getTtis().size(), 6, "0");
        }
        System.arraycopy(ttiCount.getBytes(), 0, header, 237, 6);

        // Total Number of Subtitles - Position 243-247
        System.arraycopy(StringUtils.leftPad(""+subtitleObject.getCues().size(), 6 , '0').getBytes(), 0, header, 243, 6);

        // 248..250 3 Total Number of Subtitle Groups TNG
        System.arraycopy(StringUtils.leftPad("001", 6 , '0').getBytes(), 0, header, 248, 3);

        // Maximum Number of Displayable Characters in any text row - 251..252
        // TODO
        System.arraycopy("99".getBytes(), 0, header, 251, 2);

        // Maximum Number of Displayable Rows - 253..254
        // TODO
        System.arraycopy("99".getBytes(), 0, header, 253, 2);

        // 255 1 Time Code: Status TCS
        System.arraycopy( "1".getBytes(), 0, header, 255, 1);

        // 256..263 8 Time Code: Start-of-Programme TCP
        var firstCue = subtitleObject.getCues().get(0).getStartTime();
        System.arraycopy(
                (
                        StringUtils.leftPad(""+firstCue.getHour(), 2, '0') +
                        StringUtils.leftPad(""+firstCue.getMinute(), 2, '0') +
                        StringUtils.leftPad(""+firstCue.getSecond(), 2, '0') +
                        StringUtils.leftPad(""+(firstCue.getMillisecond() / 40), 2, '0')
                ).getBytes(), 0, header, 256, 8);

        // 264..271 8 Time Code: First In-Cue TCF
        System.arraycopy(
                (
                        StringUtils.leftPad(""+firstCue.getHour(), 2, '0') +
                        StringUtils.leftPad(""+firstCue.getMinute(), 2, '0') +
                        StringUtils.leftPad(""+firstCue.getSecond(), 2, '0') +
                        StringUtils.leftPad(""+(firstCue.getMillisecond() / 40), 2, '0')
                ).getBytes(), 0, header, 264, 8);

        // 272 1 Total Number of Disks TND
        System.arraycopy( "1".getBytes(), 0, header, 272, 1);

        // 273 1 Disk Sequence Number DSN
        System.arraycopy( "1".getBytes(), 0, header, 273, 1);

        // 274..276 3 Country of Origin CO
        // TODO
        System.arraycopy( "FRA".getBytes(), 0, header, 274, 3);

        // 277..308 32 Publisher PUB
        System.arraycopy("                                 ".getBytes(), 0, header, 277, 33);

        // 309..340 32 Editor's Name EN
        System.arraycopy("                                 ".getBytes(), 0, header, 309, 33);

        // 341..372 32 Editor's Contact Details ECD
        System.arraycopy("                                 ".getBytes(), 0, header, 341, 33);

        // Write the header
        dos.write(header);
    }

    private void writeBody(SubtitleObject subtitleObject, DataOutputStream dos) throws IOException {
        List<SubtitleCue> cues = subtitleObject.getCues();

        for (int i = 0; i < cues.size(); i++) {
            SubtitleCue cue = cues.get(i);
            writeCue((BaseSubtitleCue) cue, dos, i + 1);
        }
    }

    private void writeCue(BaseSubtitleCue cue, DataOutputStream dos, int subtitleNumber) throws IOException {
        List<SubtitleLine> lines = cue.getLines();

        // Validate and adjust time codes
        SubtitleTimeCode start = cue.getStartTime();
        SubtitleTimeCode end = cue.getEndTime();
        if (start.compareTo(end) >= 0) {
            end = adjustTimeCode(end, 40); // Add 40ms to ensure TCO > TCI
        }

        // Generate the complete text with colors and CR/LF (8A) between lines
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            SubtitleLine line = lines.get(i);
            for (var text : line.getTexts()) {

                var textToAdd = text.toString();
                textToAdd = createBox(textToAdd);
                if (text.isStyled()) {
                    var styledText = (SubtitleStyledText) text;
                    textToAdd = applyColor(textToAdd, styledText.getStyle());
                }


                // remove these chars since they're added afterward during box creation
                textToAdd = textToAdd.replaceAll("[\\u008A\\n]", "");

                textBuilder.append(textToAdd);
            }

            textBuilder.append((char) 0x0A);
            textBuilder.append((char) 0x0A);
            textBuilder.append((char) 0x8A);
            textBuilder.append((char) 0x8A);
        }

        byte[] textBytes = textBuilder.toString().getBytes(new Iso6937Charset("ISO-6937-2", new String[]{}));
        int textOffset = 0;

        do {
            byte[] ttiBlock = new byte[TTI_BLOCK_SIZE];

            // Subtitle Group Number (SGN)
            ttiBlock[0] = 0x00;

            // Subtitle Number (SN)
            ttiBlock[1] = (byte) subtitleNumber;

            // Extension Block Number (EBN)
            ttiBlock[3] = (byte) 0xFF;

            // Start Time (TCI)
            ttiBlock[5] = (byte) (start.getHour());
            ttiBlock[6] = (byte) (start.getMinute());
            ttiBlock[7] = (byte) (start.getSecond());
            ttiBlock[8] = (byte) (start.getMillisecond() / 40);

            // End Time (TCO)
            ttiBlock[9] = (byte) (end.getHour());
            ttiBlock[10] = (byte) (end.getMinute());
            ttiBlock[11] = (byte) (end.getSecond());
            ttiBlock[12] = (byte) (end.getMillisecond() / 40);

            // Vertical Position (VP)
            ttiBlock[13] = getVerticalPosition(lines.size()); // Calculate vertical position
            if (cue instanceof StlCue) {
                var stlCue = (StlCue) cue;
                ttiBlock[13] = (byte) stlCue.getTtis().get(0).getVp();
            }

            // Justification Code (JC)
            ttiBlock[14] = 0x02; // Center justification

            if (cue instanceof StlCue) {
                var stlCue = (StlCue) cue;
                ttiBlock[14] = (byte) stlCue.getTtis().get(0).getJc().getValue();
            }
            if (cue instanceof VttCue) {
                var vttCue = (VttCue) cue;
                var line = vttCue.getLines().get(0).getTexts().get(0);
                if (line.isStyled()) {
                    var style = ((SubtitleStyledText) line).getStyle();
                    if (style.getTextAlign() != null) {
                        switch (style.getTextAlign()) {
                            case LEFT:
                                ttiBlock[14] = 0x01; // Left justification
                                break;
                            case RIGHT:
                                ttiBlock[14] = 0x03; // Right justification
                                break;
                            case CENTER:
                                ttiBlock[14] = 0x02; // Center justification
                                break;
                        }
                    }
                }
            }

            // Fill the Text Field
            int remainingLength = Math.min(112, textBytes.length - textOffset);
            System.arraycopy(textBytes, textOffset, ttiBlock, 16, remainingLength);
            textOffset += remainingLength;

            // Fill unused space with 8F
            for (int i = 16 + remainingLength; i < 128; i++) {
                ttiBlock[i] = (byte) 0x8F;
            }

            // Write the TTI block
            dos.write(ttiBlock);
        } while (textOffset < textBytes.length);
    }

    private String applyColor(String text, SubtitleStyle style) {
        char colorControl = getColorControlCode(style.getColor());
        // 0D = double height
        if (style.getBackgroundColor() != null) {
            char backgroundColorControl = getColorControlCode(style.getBackgroundColor());
            return (char) 0x0D + "" + backgroundColorControl + "" + (char) 0x1D + "" + colorControl + text;
        }
        return (char) 0x0D + "" + colorControl + text;
    }

    private String createBox(String text) {
        // double height
        // 2x start box
        return (char) 0x0B + "" + (char) 0x0B + "" + text;
    }

    private char getColorControlCode(String color) {
        switch (color.toLowerCase()) {
            case "black": return 0x00; // Black
            case "red": return 0x01;   // Red
            case "green": return 0x02; // Green
            case "yellow": return 0x03; // Yellow
            case "blue": return 0x04;  // Blue
            case "magenta": return 0x05; // Magenta
            case "cyan": return 0x06;  // Cyan
            default: return 0x07;      // Default to white
        }
    }

    private byte getVerticalPosition(int lineCount) {
        // Example logic: position subtitles at a default vertical position based on line count
        int basePosition = 20; // Default starting position
        return (byte) Math.max(0, basePosition - lineCount);
    }

    private SubtitleTimeCode adjustTimeCode(SubtitleTimeCode timeCode, int millisecondsToAdd) {
        int totalMilliseconds = timeCode.getMillisecond() + millisecondsToAdd;

        int hours = totalMilliseconds / 3600000;
        totalMilliseconds %= 3600000;
        int minutes = totalMilliseconds / 60000;
        totalMilliseconds %= 60000;
        int seconds = totalMilliseconds / 1000;
        int milliseconds = totalMilliseconds % 1000;

        return new SubtitleTimeCode(hours, minutes, seconds, milliseconds);
    }
}
