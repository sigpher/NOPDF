package com.github.barteksc.pdfviewer.engine;

/**
 * Engine-neutral size in fractional units, as page sizes are measured for layout.
 */
public class EngineSizeF {

    private final float width;
    private final float height;

    public EngineSizeF(float width, float height) {
        this.width = width;
        this.height = height;
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    public EngineSize toSize() {
        return new EngineSize((int) width, (int) height);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EngineSizeF)) {
            return false;
        }
        EngineSizeF other = (EngineSizeF) o;
        return Float.compare(width, other.width) == 0 && Float.compare(height, other.height) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * Float.floatToIntBits(width) + Float.floatToIntBits(height);
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }
}
