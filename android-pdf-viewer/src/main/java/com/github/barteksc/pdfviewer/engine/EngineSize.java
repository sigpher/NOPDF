package com.github.barteksc.pdfviewer.engine;

/**
 * Engine-neutral integer size, mirroring {@code com.shockwave.pdfium.util.Size}.
 */
public class EngineSize {

    private final int width;
    private final int height;

    public EngineSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public EngineSizeF toSizeF() {
        return new EngineSizeF(width, height);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EngineSize)) {
            return false;
        }
        EngineSize other = (EngineSize) o;
        return width == other.width && height == other.height;
    }

    @Override
    public int hashCode() {
        return 31 * width + height;
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }
}
