/*
 *  This file is part of the noOp organization .
 *
 *  (c) Cyrille Lebeaupin <clebeaupin@noop.fr>
 *
 *  For the full copyright and license information, please view the LICENSE
 *  file that was distributed with this source code.
 *
 */

package fr.noop.subtitle.ttml;

import fr.noop.subtitle.base.BaseSubtitleCue;
import fr.noop.subtitle.model.SubtitleCue;
import fr.noop.subtitle.model.SubtitleLine;
import fr.noop.subtitle.model.SubtitleRegionCue;
import fr.noop.subtitle.stl.StlCue;
import fr.noop.subtitle.stl.StlDefaultRegion;
import fr.noop.subtitle.stl.StlTti;
import fr.noop.subtitle.util.*;

import java.util.ArrayList;

/**
 * Created by clebeaupin on 11/10/15.
 */
public class TtmlCue extends BaseSubtitleCue implements SubtitleRegionCue {
    private SubtitleRegion region;
    private SubtitleStyle style;

    public TtmlCue(SubtitleCue cue) {
        super(cue);

        if (cue instanceof StlCue) {
            this.style = new SubtitleStyle();

            // handle stl cue regions specially because vertical positions
            // are managed using carriage returns there
            var stlCue = (StlCue) cue;

            var jc = stlCue.getTtis().get(0).getJc();
            switch (jc) {
                case CENTER:
                    this.style.setTextAlign(SubtitleStyle.TextAlign.CENTER);
                    break;
                case LEFT:
                    this.style.setTextAlign(SubtitleStyle.TextAlign.LEFT);
                    break;
                case RIGHT:
                    this.style.setTextAlign(SubtitleStyle.TextAlign.RIGHT);
                    break;
            }

            var vp = stlCue.getTtis().get(0).getVp();
            if (vp < 20) {
                var lastLine = this.getLines().get(this.getLines().size()-1);
                var lastText = (SubtitleStyledText) lastLine.getTexts().get(lastLine.getTexts().size()-1);
                var builder = new StringBuilder();
                for (int i = 0; i < 20 - vp - this.getLines().size() - 1; i++) {
                    builder.append("\n");
                }

                var text = new SubtitleStyledText(lastText.toString() + builder, lastText.getStyle());
                lastLine.getTexts().set(lastLine.getTexts().size()-1, text);
            }
        } else if (cue instanceof SubtitleRegionCue) {
            this.setRegion(new SubtitleRegion(((SubtitleRegionCue) cue).getRegion()));
        }
    }

    public void setRegion(SubtitleRegion region) {
        this.region = region;
    }

    @Override
    public SubtitleRegion getRegion() {
        return this.region;
    }

    public SubtitleStyle getStyle() {
        return style;
    }
}
