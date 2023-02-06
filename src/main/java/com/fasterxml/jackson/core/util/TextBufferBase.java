package com.fasterxml.jackson.core.util;

import java.io.IOException;
import java.io.Writer;

public abstract class TextBufferBase {
    public abstract String contentsAsString();

    public abstract int contentsToWriter(Writer writer) throws IOException;

    public abstract char[] getTextBuffer();

    public abstract int size();

    public abstract int getTextOffset();

    public abstract void releaseBuffers();

    public abstract void resetWithString(String valueStr);

    public abstract long contentsAsLong(boolean numberNegative);

    public abstract int contentsAsInt(boolean numberNegative);
}
