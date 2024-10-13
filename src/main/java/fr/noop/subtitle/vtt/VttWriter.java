/*
 *  This file is part of the noOp organization .
 *
 *  (c) Cyrille Lebeaupin <clebeaupin@noop.fr>
 *
 *  For the full copyright and license information, please view the LICENSE
 *  file that was distributed with this source code.
 *
 */

package fr.noop.subtitle.vtt;

import fr.noop.subtitle.base.BaseSubtitleObject;
import fr.noop.subtitle.model.SubtitleCue;
import fr.noop.subtitle.model.SubtitleObject;
import fr.noop.subtitle.model.SubtitleWriter;
import fr.noop.subtitle.stl.StlCue;
import fr.noop.subtitle.util.SubtitleStyledText;
import fr.noop.subtitle.util.SubtitleTimeCode;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;

/**
 * Created by clebeaupin on 11/10/15.
 */
public class VttWriter implements SubtitleWriter {
    private String charset; // Charset used to encode file

    public VttWriter(String charset) {
        this.charset = charset;
    }

    @Override
    public void write(SubtitleObject subtitleObject, OutputStream os) throws IOException {
        try {
            // Write header
            os.write(new String("WEBVTT\n\n").getBytes(this.charset));

            os.write(new String("""

STYLE
::cue(.black) {
 color: black;
}

STYLE
::cue(.red) {
 color: red;
}

STYLE
::cue(.green) {
 color: green;
}

STYLE
::cue(.yellow) {
 color: yellow;
}

STYLE
::cue(.blue) {
 color: blue;
}

STYLE
::cue(.magenta) {
 color: magenta;
}

STYLE
::cue(.cyan) {
 color: cyan;
}

STYLE
::cue(.white) {
 color: white;
}

STYLE
::cue(.bg_black) {
 background-color: black;
}

STYLE
::cue(.bg_red) {
 background-color: red;
}

STYLE
::cue(.bg_green) {
 background-color: green;
}

STYLE
::cue(.bg_yellow) {
 background-color: yellow;
}

STYLE
::cue(.bg_blue) {
 background-color: blue;
}

STYLE
::cue(.bg_magenta) {
 background-color: magenta;
}

STYLE
::cue(.bg_cyan) {
 background-color: cyan;
}

STYLE
::cue(.bg_white) {
 background-color: white;
}

                    """).getBytes(this.charset));


            // Write cues
            for (SubtitleCue cue : subtitleObject.getCues()) {
                if (cue.getId() != null) {
                    // Write number of subtitle
                    String number = String.format("%s\n", cue.getId());
                    os.write(number.getBytes(this.charset));
                }

                // Write Start time and end time with positions if present
                String startToEnd = String.format("%s --> %s",
                        this.formatTimeCode(cue.getStartTime()),
                        this.formatTimeCode(cue.getEndTime()));


                // Add position (horizontal) if available
                if (cue instanceof StlCue) {
                    var stlCue = (StlCue) cue;
                    if (stlCue.getHorizontalPosition() != 50) {
                        startToEnd += String.format(" align:%s", stlCue.getHorizontalPosition() == 0 ? "left" : "right");
                    }

                    // Add line (vertical position) if available
                    if (stlCue.getVerticalPosition() != 95) {
                        startToEnd += String.format(" line:%d%%", stlCue.getVerticalPosition());
                    }
                }

                // Add newline after the cue time line
                startToEnd += " \n";
                os.write(startToEnd.getBytes(this.charset));

                // Write text
                for (var line : cue.getLines()) {
                    for (var text : line.getTexts()) {
                        try {
                            if (text instanceof SubtitleStyledText) {
                                SubtitleStyledText styledText = (SubtitleStyledText) text;
                                os.write(String.format("<c.%s>%s</c>\n", styledText.getStyle().getColor(), styledText).getBytes(this.charset));
                            } else {
                                os.write(String.format("%s\n", text).getBytes(this.charset));
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }

                // Write empty line
                os.write("\n".getBytes(this.charset));
            }
        } catch (UnsupportedEncodingException e) {
            throw new IOException("Encoding error in input subtitle");
        }
    }

    private String formatTimeCode(SubtitleTimeCode timeCode) {
        return String.format("%02d:%02d:%02d.%03d",
                timeCode.getHour(),
                timeCode.getMinute(),
                timeCode.getSecond(),
                timeCode.getMillisecond());
    }
}
