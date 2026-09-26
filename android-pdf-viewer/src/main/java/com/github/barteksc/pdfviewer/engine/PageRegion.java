package com.github.barteksc.pdfviewer.engine;

/**
 * Turns the viewer's page-relative tile rectangle into page points, and the device
 * transform that lands that rectangle on a bitmap.
 *
 * <p>The viewer tiles pages in <em>fractions of the page</em>: {@code PagesLoader} cuts a page
 * into a {@code rows x cols} grid and hands each cell a {@link android.graphics.RectF} in 0..1
 * page-relative coordinates, which is also what {@code PagePart} carries and what
 * {@code PDFView.drawPart} later stretches the rendered bitmap onto. Keeping that rectangle in
 * fractions all the way into the engine is what makes the two ends provably agree: a tile's
 * bitmap covers exactly the area its {@code pageRelativeBounds} names, because both are derived
 * from the same four numbers.
 *
 * <p>This exists as a separate, Android-free class so the arithmetic can be unit tested. Nothing
 * here touches {@code Bitmap}, {@code Rect} or {@code Matrix}, whose methods throw outside a
 * real framework.
 *
 * <p>Note that {@link #deviceTransform} scales the two axes independently. The bitmap a tile is
 * given is a fixed {@code Constants.PART_SIZE} square, while the tile's slice of the page is
 * generally not square, so a single "cover the bitmap" scale would paint a superset of the
 * slice — neighbouring content bleeding into every tile and duplicated along the seams between
 * them. Scaling per axis maps the slice onto the bitmap exactly; {@code PDFView.drawPart}
 * stretches the result back on draw, which cancels the pixel aspect out again.
 */
public final class PageRegion {

    private PageRegion() {
    }

    /** A rectangle in page points, origin at the top-left of the page. */
    public static final class Region {

        public final float left;
        public final float top;
        public final float right;
        public final float bottom;

        public Region(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public float width() {
            return right - left;
        }

        public float height() {
            return bottom - top;
        }

        @Override
        public String toString() {
            return "Region{" + left + "," + top + "-" + right + "," + bottom + "}";
        }
    }

    /**
     * Converts a page-relative rectangle (fractions of the page, 0..1) to page points.
     *
     * @param pageWidthPt  page width in points, as {@link PdfEngine#getPageSize} reports it
     * @param pageHeightPt page height in points
     */
    public static Region of(float relLeft, float relTop, float relRight, float relBottom,
                            int pageWidthPt, int pageHeightPt) {
        return new Region(
                relLeft * pageWidthPt,
                relTop * pageHeightPt,
                relRight * pageWidthPt,
                relBottom * pageHeightPt);
    }

    /**
     * The transform that maps {@code region} exactly onto a {@code bitmapWidth x bitmapHeight}
     * bitmap: {@code deviceX = pageX * scaleX + offsetX}, and likewise for Y.
     *
     * @return {@code {scaleX, scaleY, offsetX, offsetY}}, ready to be handed to a matrix
     */
    public static float[] deviceTransform(Region region, int bitmapWidth, int bitmapHeight) {
        float scaleX = bitmapWidth / region.width();
        float scaleY = bitmapHeight / region.height();
        return new float[]{
                scaleX,
                scaleY,
                -region.left * scaleX,
                -region.top * scaleY,
        };
    }
}
