/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.imaging.palette;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.imaging.ImageWriteException;
import org.apache.commons.imaging.util.Debug;

public class MedianCutQuantizer {
    private final boolean ignoreAlpha;

    public MedianCutQuantizer(final boolean ignore_alpha) {
        this.ignoreAlpha = ignore_alpha;
    }

    private static class ColorCount {
        public final int argb;
        public int count = 0;
        public final int alpha, red, green, blue;

        public ColorCount(final int argb) {
            this.argb = argb;

            alpha = 0xff & (argb >> 24);
            red = 0xff & (argb >> 16);
            green = 0xff & (argb >> 8);
            blue = 0xff & (argb >> 0);

            // super.hashCode();
        }

        @Override
        public int hashCode() {
            return argb;
        }

        @Override
        public boolean equals(final Object o) {
            if (o instanceof ColorCount) {
                final ColorCount other = (ColorCount) o;
                return other.argb == this.argb;
            }
            return false;
        }

    }

    private class ColorGroup {
        // public final ColorGroup parent;
        public ColorGroupCut cut = null;
        // public final List children = new ArrayList();
        public int palette_index = -1;

        public final List<ColorCount> color_counts;
        public int min_red = Integer.MAX_VALUE;
        public int max_red = Integer.MIN_VALUE;
        public int min_green = Integer.MAX_VALUE;
        public int max_green = Integer.MIN_VALUE;
        public int min_blue = Integer.MAX_VALUE;
        public int max_blue = Integer.MIN_VALUE;
        public int min_alpha = Integer.MAX_VALUE;
        public int max_alpha = Integer.MIN_VALUE;

        public final int alpha_diff;
        public final int red_diff;
        public final int green_diff;
        public final int blue_diff;

        public final int max_diff;
        public final int diff_total;

        public ColorGroup(final List<ColorCount> color_counts)
                throws ImageWriteException {
            this.color_counts = color_counts;

            if (color_counts.size() < 1) {
                throw new ImageWriteException("empty color_group");
            }

            for (int i = 0; i < color_counts.size(); i++) {
                final ColorCount color = color_counts.get(i);

                min_alpha = Math.min(min_alpha, color.alpha);
                max_alpha = Math.max(max_alpha, color.alpha);
                min_red = Math.min(min_red, color.red);
                max_red = Math.max(max_red, color.red);
                min_green = Math.min(min_green, color.green);
                max_green = Math.max(max_green, color.green);
                min_blue = Math.min(min_blue, color.blue);
                max_blue = Math.max(max_blue, color.blue);
            }

            alpha_diff = max_alpha - min_alpha;
            red_diff = max_red - min_red;
            green_diff = max_green - min_green;
            blue_diff = max_blue - min_blue;
            max_diff = Math.max(
                    ignoreAlpha ? red_diff : Math.max(alpha_diff, red_diff),
                    Math.max(green_diff, blue_diff));
            diff_total = (ignoreAlpha ? 0 : alpha_diff) + red_diff + green_diff
                    + blue_diff;

        }

        public boolean contains(final int argb) {
            final int alpha = 0xff & (argb >> 24);
            final int red = 0xff & (argb >> 16);
            final int green = 0xff & (argb >> 8);
            final int blue = 0xff & (argb >> 0);

            if (!ignoreAlpha && (alpha < min_alpha || alpha > max_alpha)) {
                return false;
            }
            if (red < min_red || red > max_red) {
                return false;
            }
            if (green < min_green || green > max_green) {
                return false;
            }
            if (blue < min_blue || blue > max_blue) {
                return false;
            }
            return true;
        }

        public int getMedianValue() {
            long count_total = 0;
            long alpha_total = 0, red_total = 0, green_total = 0, blue_total = 0;

            for (int i = 0; i < color_counts.size(); i++) {
                final ColorCount color = color_counts.get(i);

                count_total += color.count;
                alpha_total += color.count * color.alpha;
                red_total += color.count * color.red;
                green_total += color.count * color.green;
                blue_total += color.count * color.blue;
            }

            final int alpha = ignoreAlpha ? 0xff : (int) Math
                    .round((double) alpha_total / count_total);
            final int red = (int) Math.round((double) red_total / count_total);
            final int green = (int) Math.round((double) green_total / count_total);
            final int blue = (int) Math.round((double) blue_total / count_total);

            return (alpha << 24) | (red << 16) | (green << 8) | blue;
        }

        @Override
        public String toString() {
            return "{ColorGroup. min_red: " + Integer.toHexString(min_red)
                    + ", max_red: " + Integer.toHexString(max_red)
                    + ", min_green: " + Integer.toHexString(min_green)
                    + ", max_green: " + Integer.toHexString(max_green)
                    + ", min_blue: " + Integer.toHexString(min_blue)
                    + ", max_blue: " + Integer.toHexString(max_blue)
                    + ", min_alpha: " + Integer.toHexString(min_alpha)
                    + ", max_alpha: " + Integer.toHexString(max_alpha)
                    + ", max_diff: " + Integer.toHexString(max_diff)
                    + ", diff_total: " + diff_total + "}";
        }

    }

    public Map<Integer, ColorCount> groupColors1(final BufferedImage image, final int max,
            final int mask) {
        final Map<Integer, ColorCount> color_map = new HashMap<Integer, ColorCount>();

        final int width = image.getWidth();
        final int height = image.getHeight();

        final int row[] = new int[width];
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int argb = row[x];

                if (ignoreAlpha) {
                    argb &= 0xffffff;
                }
                argb &= mask;

                ColorCount color = color_map.get(argb);
                if (color == null) {
                    color = new ColorCount(argb);
                    color_map.put(argb, color);
                    if (color_map.keySet().size() > max) {
                        return null;
                    }
                }
                color.count++;
            }
        }

        return color_map;
    }

    public Map<Integer, ColorCount> groupColors(final BufferedImage image,
            final int max_colors) {
        final int max = Integer.MAX_VALUE;

        for (int i = 0; i < 8; i++) {
            int mask = 0xff & (0xff << i);
            mask = mask | (mask << 8) | (mask << 16) | (mask << 24);

            Debug.debug("mask(" + i + ")",
                    mask + " (" + Integer.toHexString(mask) + ")");

            final Map<Integer, ColorCount> result = groupColors1(image, max, mask);
            if (result != null) {
                return result;
            }
        }
        throw new Error("");
    }

    public Palette process(final BufferedImage image, final int max_colors, final boolean verbose)
            throws ImageWriteException {
        final Map<Integer, ColorCount> color_map = groupColors(image, max_colors);

        final int discrete_colors = color_map.keySet().size();
        if (discrete_colors <= max_colors) {
            if (verbose) {
                Debug.debug("lossless palette: " + discrete_colors);
            }

            final int palette[] = new int[discrete_colors];
            final List<ColorCount> color_counts = new ArrayList<ColorCount>(
                    color_map.values());

            for (int i = 0; i < color_counts.size(); i++) {
                final ColorCount color_count = color_counts.get(i);
                palette[i] = color_count.argb;
                if (ignoreAlpha) {
                    palette[i] |= 0xff000000;
                }
            }

            return new SimplePalette(palette);
        }

        if (verbose) {
            Debug.debug("discrete colors: " + discrete_colors);
        }

        final List<ColorGroup> color_groups = new ArrayList<ColorGroup>();
        final ColorGroup root = new ColorGroup(new ArrayList<ColorCount>(
                color_map.values()));
        color_groups.add(root);

        final Comparator<ColorGroup> comparator = new Comparator<ColorGroup>() {
            public int compare(final ColorGroup cg1, final ColorGroup cg2) {
                if (cg1.max_diff == cg2.max_diff) {
                    return cg2.diff_total - cg1.diff_total;
                }
                return cg2.max_diff - cg1.max_diff;
            }
        };

        while (color_groups.size() < max_colors) {
            Collections.sort(color_groups, comparator);

            final ColorGroup color_group = color_groups.get(0);

            if (color_group.max_diff == 0) {
                break;
            }
            if (!ignoreAlpha
                    && color_group.alpha_diff > color_group.red_diff
                    && color_group.alpha_diff > color_group.green_diff
                    && color_group.alpha_diff > color_group.blue_diff) {
                doCut(color_group, ColorComponent.ALPHA, color_groups);
            } else if (color_group.red_diff > color_group.green_diff
                    && color_group.red_diff > color_group.blue_diff) {
                doCut(color_group, ColorComponent.RED, color_groups);
            } else if (color_group.green_diff > color_group.blue_diff) {
                doCut(color_group, ColorComponent.GREEN, color_groups);
            } else {
                doCut(color_group, ColorComponent.BLUE, color_groups);
            }
        }

        final int palette_size = color_groups.size();
        if (verbose) {
            Debug.debug("palette size: " + palette_size);
        }

        final int palette[] = new int[palette_size];

        for (int i = 0; i < color_groups.size(); i++) {
            final ColorGroup color_group = color_groups.get(i);

            palette[i] = color_group.getMedianValue();

            color_group.palette_index = i;

            if (color_group.color_counts.size() < 1) {
                throw new ImageWriteException("empty color_group: "
                        + color_group);
            }
        }

        if (palette_size > discrete_colors) {
            throw new ImageWriteException("palette_size>discrete_colors");
        }

        return new MedianCutPalette(root, palette);
    }

    private static enum ColorComponent {
        ALPHA {
            @Override
            public int argbComponent(int argb) {
                return (argb >> 24) & 0xff;
            }
        },
        RED {
            @Override
            public int argbComponent(int argb) {
                return (argb >> 16) & 0xff;
            }
        },
        GREEN {
            @Override
            public int argbComponent(int argb) {
                return (argb >> 8) & 0xff;
            }
        },
        BLUE {
            @Override
            public int argbComponent(int argb) {
                return (argb & 0xff);
            }
        };
        
        public abstract int argbComponent(int argb);
    }

    private void doCut(final ColorGroup color_group, final ColorComponent mode,
            final List<ColorGroup> color_groups) throws ImageWriteException {
        int count_total = 0;
        for (int i = 0; i < color_group.color_counts.size(); i++) {
            final ColorCount color_count = color_group.color_counts.get(i);
            count_total += color_count.count;
        }

        final Comparator<ColorCount> comparator = new Comparator<ColorCount>() {
            public int compare(final ColorCount c1, final ColorCount c2) {
                switch (mode) {
                case ALPHA:
                    return c1.alpha - c2.alpha;
                case RED:
                    return c1.red - c2.red;
                case GREEN:
                    return c1.green - c2.green;
                case BLUE:
                    return c1.blue - c2.blue;
                default:
                    return 0;
                }
            }
        };

        Collections.sort(color_group.color_counts, comparator);
        final int count_half = (int) Math.round((double) count_total / 2);
        int old_count = 0, new_count = 0;
        int median_index;
        for (median_index = 0; median_index < color_group.color_counts.size(); median_index++) {
            final ColorCount color_count = color_group.color_counts.get(median_index);

            new_count += color_count.count;

            if (new_count < count_half) {
                old_count = new_count;
                continue;
            }
            break;
        }

        if (median_index == color_group.color_counts.size() - 1) {
            median_index--;
        } else if (median_index > 0) {
            final int new_diff = Math.abs(new_count - count_half);
            final int old_diff = Math.abs(count_half - old_count);
            if (old_diff < new_diff) {
                median_index--;
            }
        }

        color_groups.remove(color_group);
        final List<ColorCount> color_counts1 = new ArrayList<ColorCount>(
                color_group.color_counts.subList(0, median_index + 1));
        final List<ColorCount> color_counts2 = new ArrayList<ColorCount>(
                color_group.color_counts.subList(median_index + 1,
                        color_group.color_counts.size()));

        ColorGroup less, more;
        less = new ColorGroup(new ArrayList<ColorCount>(color_counts1));
        color_groups.add(less);
        more = new ColorGroup(new ArrayList<ColorCount>(color_counts2));
        color_groups.add(more);

        final ColorCount median_value = color_group.color_counts
                .get(median_index);
        int limit;
        switch (mode) {
        case ALPHA:
            limit = median_value.alpha;
            break;
        case RED:
            limit = median_value.red;
            break;
        case GREEN:
            limit = median_value.green;
            break;
        case BLUE:
            limit = median_value.blue;
            break;
        default:
            throw new Error("Bad mode.");
        }
        color_group.cut = new ColorGroupCut(less, more, mode, limit);
    }

    private static class ColorGroupCut {
        public final ColorGroup less, more;
        public final ColorComponent mode;
        public final int limit;

        public ColorGroupCut(final ColorGroup less, final ColorGroup more, final ColorComponent mode,
                final int limit) {
            this.less = less;
            this.more = more;
            this.mode = mode;
            this.limit = limit;
        }

        public ColorGroup getColorGroup(final int argb) {
            int value = mode.argbComponent(argb);
            if (value <= limit) {
                return less;
            }
            return more;
        }

    }

    public static class MedianCutPalette extends SimplePalette {
        private final ColorGroup root;

        public MedianCutPalette(final ColorGroup root, final int palette[]) {
            super(palette);
            this.root = root;
        }

        @Override
        public int getPaletteIndex(final int rgb) {
            ColorGroup cg = root;

            while (cg.cut != null) {
                cg = cg.cut.getColorGroup(rgb);
            }

            return cg.palette_index;
        }
    }

}
