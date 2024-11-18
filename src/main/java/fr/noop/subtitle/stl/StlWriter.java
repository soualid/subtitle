package fr.noop.subtitle.stl;

import fr.noop.subtitle.base.BaseSubtitleCue;
import fr.noop.subtitle.model.*;
import fr.noop.subtitle.util.SubtitleTimeCode;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;

/**
 * Corrected StlWriter for generating valid STL files.
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
            System.arraycopy(languageCode.getBytes(StandardCharsets.US_ASCII), 0, header, 14, 2);
        } else {
            System.arraycopy("0F".getBytes(StandardCharsets.US_ASCII), 0, header, 14, 2); // Default: '0F' (french)
        }

        // Original Program Title (OPT) - Position 15-47
        String title = (String) subtitleObject.getProperty(SubtitleObject.Property.TITLE);
        if (title != null) {
            byte[] titleBytes = title.getBytes(StandardCharsets.ISO_8859_1);
            int length = Math.min(titleBytes.length, 33);
            System.arraycopy(titleBytes, 0, header, 15, length);
        }

        // Creation Date (CD) - Position 224-229
        var df = new SimpleDateFormat("yyMMdd").format(System.currentTimeMillis());
        System.arraycopy(df.getBytes(), 0, header, 224, 6);

        // Revision Date (RD) - Position 230-235
        System.arraycopy(df.getBytes(), 0, header, 230, 6);

        // TNB 238..242
        System.arraycopy(StringUtils.leftPad(subtitleObject.getCues().size()+"", 5, '0').getBytes(), 0, header, 238, 5);

        // TNB 243..247
        System.arraycopy(StringUtils.leftPad(subtitleObject.getCues().size()+"", 5, '0').getBytes(), 0, header, 243, 5);

        // MNC 251..252
        System.arraycopy("36".getBytes(), 0, header, 251, 2);

        // MNR 253..254
        System.arraycopy("23".getBytes(), 0, header, 253, 2);

        // TCP 256..263
        System.arraycopy("10000000".getBytes(), 0, header, 256, 8);

        // TCF 264..271
        var firstCue = subtitleObject.getCues().get(0);
        var firstCueString = StringUtils.leftPad(firstCue.getStartTime().getHour()+"", 2, '0') +
                StringUtils.leftPad(firstCue.getStartTime().getMinute()+"", 2, '0') +
                StringUtils.leftPad(firstCue.getStartTime().getSecond()+"", 2, '0') +
                StringUtils.leftPad((firstCue.getStartTime().getMillisecond()/40)+"", 2, '0');

        System.arraycopy(firstCueString.getBytes(), 0, header, 264, 8);

        // User-defined area (Position 20-1024)
        for (int i = 272; i < HEADER_SIZE; i++) {
            header[i] = 0x20; // Fill
        }

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

        // Generate the complete text with CR/LF (8A) between lines
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            SubtitleLine line = lines.get(i);
            String text = line.getTexts().stream().map(SubtitleText::toString).reduce("", String::concat).trim();

            textBuilder.append((char) 0x0D);
            textBuilder.append((char) 0x0B);
            textBuilder.append((char) 0x0B);
            textBuilder.append(text);
            textBuilder.append((char) 0x0A);
            textBuilder.append((char) 0x0A);

            if (i < lines.size() - 1) {
                textBuilder.append((char) 0x8A); // Add CR/LF between lines
            }
        }

        byte[] textBytes = textBuilder.toString().getBytes(StandardCharsets.ISO_8859_1);
        int textOffset = 0;
        int blockIndex = 0;

        while (textOffset < textBytes.length || blockIndex == 0) {
            byte[] gsiBlock = new byte[TTI_BLOCK_SIZE];

            // Subtitle Group Number (SGN)
            gsiBlock[0] = 0x00;

            // Subtitle Number (SN)
            gsiBlock[1] = (byte) (subtitleNumber - 1);
            //gsiBlock[2] = (byte) ((subtitleNumber >> 8) & 0xFF);

            // Extension Block Number (EBN)
            gsiBlock[3] = (byte) (0xFF);
            //gsiBlock[3] = (byte) (blockIndex & 0xFF);

            // Cumulative Status (CS)
            gsiBlock[4] = 0x00; // Not cumulative

            // Start Time (TCI)
            gsiBlock[5] = (byte) (start.getHour());
            gsiBlock[6] = (byte) (start.getMinute());
            gsiBlock[7] = (byte) (start.getSecond());
            gsiBlock[8] = (byte) (start.getMillisecond() / 40);

            // End Time (TCO)
            gsiBlock[9] = (byte) (end.getHour());
            gsiBlock[10] = (byte) (end.getMinute());
            gsiBlock[11] = (byte) (end.getSecond());
            gsiBlock[12] = (byte) (end.getMillisecond() / 40);

            // Vertical Position (VP)
            gsiBlock[13] = 0x00; // Default VP; adapt as needed

            // Justification Code (JC)
            gsiBlock[14] = 0x02; // Default to center justification

            // Comment Flag (CF)
            gsiBlock[15] = 0x00; // Default to no comment

            // Fill the Text Field
            int remainingLength = Math.min(112, textBytes.length - textOffset);
            System.arraycopy(textBytes, textOffset, gsiBlock, 16, remainingLength);
            textOffset += remainingLength;

            // Add feeder (8F) to unused space or at the end of the last block
            if (textOffset >= textBytes.length) {
                if (remainingLength < 112) {
                    gsiBlock[16 + remainingLength] = (byte) 0x8F; // Add feeder at the end of the text
                }
                for (int i = 16 + remainingLength + 1; i < 128; i++) {
                    gsiBlock[i] = (byte) 0x8F; // Fill unused space with 8F
                }
            } else {
                // Fill unused space in intermediate blocks with 8F
                for (int i = 16 + remainingLength; i < 128; i++) {
                    gsiBlock[i] = (byte) 0x8F;
                }
            }

            // Write the TTI block
            dos.write(gsiBlock);
            blockIndex++;
        }
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

    private byte createStyleByte(SubtitleLine line) {
        byte style = 0;

        if (line instanceof StlTti) {
            StlTti tti = (StlTti) line;

            // Set color
            if (tti.getCf() > 0 && StlTti.TextColor.hasEnum(tti.getCf())) {
                style |= StlTti.TextColor.getEnum(tti.getCf()).getValue();
            }

            // Set justification
            if (tti.getJc() != null) {
                style |= tti.getJc().getValue() << 4;
            }

            // Set vertical position
            style |= (tti.getVp() & 0x0F) << 6;
        }

        return style;
    }
}
