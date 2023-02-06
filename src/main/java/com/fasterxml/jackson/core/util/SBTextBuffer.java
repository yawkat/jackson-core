package com.fasterxml.jackson.core.util;

import com.fasterxml.jackson.core.io.NumberInput;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * TextBuffer is a class similar to {@link StringBuffer}, with
 * following differences:
 *<ul>
 *  <li>TextBuffer uses segments character arrays, to avoid having
 *     to do additional array copies when array is not big enough.
 *     This means that only reallocating that is necessary is done only once:
 *     if and when caller
 *     wants to access contents in a linear array (char[], String).
 *    </li>
*  <li>TextBuffer can also be initialized in "shared mode", in which
*     it will just act as a wrapper to a single char array managed
*     by another object (like parser that owns it)
 *    </li>
 *  <li>TextBuffer is not synchronized.
 *    </li>
 * </ul>
 */
public final class SBTextBuffer extends TextBufferBase
{
    final static char[] NO_CHARS = new char[0];

    /**
     * Let's start with sizable but not huge buffer, will grow as necessary
     *<p>
     * Reduced from 1000 down to 500 in 2.10.
     */
    final static int MIN_SEGMENT_LEN = 500;

    /**
     * Let's limit maximum segment length to something sensible.
     * For 2.10, let's limit to using 64kc chunks (128 kB) -- was 256kC/512kB up to 2.9
     */
    final static int MAX_SEGMENT_LEN = 0x10000;

    /*
    /**********************************************************
    /* Configuration:
    /**********************************************************
     */

    private final BufferRecycler _allocator;

    /*
    /**********************************************************
    /* Shared input buffers
    /**********************************************************
     */

    /**
     * Shared input buffer; stored here in case some input can be returned
     * as is, without being copied to collector's own buffers. Note that
     * this is read-only for this Object.
     */
    private char[] _inputBuffer;

    /**
     * Character offset of first char in input buffer; -1 to indicate
     * that input buffer currently does not contain any useful char data
     */
    private int _inputStart;

    private int _inputLen;

    /*
    /**********************************************************
    /* Aggregation segments (when not using input buf)
    /**********************************************************
     */

    /**
     * List of segments prior to currently active segment.
     */
    private ArrayList<StringBuilder> _segments;

    /**
     * Flag that indicates whether _seqments is non-empty
     */
    private boolean _hasSegments;

    // // // Currently used segment; not (yet) contained in _seqments

    /**
     * Amount of characters in segments in {@link #_segments}
     */
    private int _segmentSize;

    private StringBuilder _currentSegment;

    /*
    /**********************************************************
    /* Caching of results
    /**********************************************************
     */

    /**
     * String that will be constructed when the whole contents are
     * needed; will be temporarily stored in case asked for again.
     */
    private String _resultString;

    private char[] _resultArray;

    /*
    /**********************************************************
    /* Life-cycle
    /**********************************************************
     */

    public SBTextBuffer(BufferRecycler allocator) {
        _allocator = allocator;
    }

    // @since 2.10
    protected SBTextBuffer(BufferRecycler allocator, StringBuilder initialSegment) {
        this(allocator);
        _currentSegment = initialSegment;
        _inputStart = -1;
    }

    /**
     * Factory method for constructing an instance with no allocator, and
     * with initial full segment.
     *
     * @param initialSegment Initial, full segment to use for creating buffer (buffer
     *   {@link #size()} would return length of {@code initialSegment})
     *
     * @return TextBuffer constructed
     *
     * @since 2.10
     */
    public static SBTextBuffer fromInitial(StringBuilder initialSegment) {
        return new SBTextBuffer(null, initialSegment);
    }

    // Helper method used to find a buffer to use, ideally one
    // recycled earlier.
    private char[] buf(int needed)
    {
        if (_allocator != null) {
            return _allocator.allocCharBuffer(BufferRecycler.CHAR_TEXT_BUFFER, needed);
        }
        return new char[Math.max(needed, MIN_SEGMENT_LEN)];
    }

    // Helper method used to find a buffer to use, ideally one
    // recycled earlier.
    private StringBuilder bui(int needed)
    {
        if (_allocator != null) {
            return _allocator.allocStringBuilder(BufferRecycler.CHAR_TEXT_BUFFER, needed);
        }
        return new StringBuilder(Math.max(needed, MIN_SEGMENT_LEN));
    }

    private void clearSegments()
    {
        _hasSegments = false;
        /* Let's start using _last_ segment from list; for one, it's
         * the biggest one, and it's also most likely to be cached
         */
        /* 28-Aug-2009, tatu: Actually, the current segment should
         *   be the biggest one, already
         */
        //_currentSegment = _segments.get(_segments.size() - 1);
        _segments.clear();
        _currentSegment.setLength(0);
        _segmentSize = 0;
    }

    /*
    /**********************************************************
    /* Accessors for implementing public interface
    /**********************************************************
     */

    /**
     * @return Number of characters currently stored in this buffer
     */
    public int size() {
        if (_inputStart >= 0) { // shared copy from input buf
            return _inputLen;
        }
        if (_resultArray != null) {
            return _resultArray.length;
        }
        if (_resultString != null) {
            return _resultString.length();
        }
        // local segmented buffers
        return _segmentSize + _currentSegment.length();
    }

    public int getTextOffset() {
        /* Only shared input buffer can have non-zero offset; buffer
         * segments start at 0, and if we have to create a combo buffer,
         * that too will start from beginning of the buffer
         */
        return (_inputStart >= 0) ? _inputStart : 0;
    }

    @Override
    public void releaseBuffers() {
        // inlined `resetWithEmpty()` (except leaving `_resultString` as-is
        {
            _inputStart = -1;
            if (_currentSegment != null) {
                _currentSegment.setLength(0);
            }
            _inputLen = 0;

            _inputBuffer = null;
            // note: _resultString retained (see https://github.com/FasterXML/jackson-databind/issues/2635
            // for reason)
            _resultArray = null; // should this be retained too?

            if (_hasSegments) {
                clearSegments();
            }
        }

        if (_allocator != null) {
            if (_currentSegment != null) {
                // And then return that array
                StringBuilder buf = _currentSegment;
                _currentSegment = null;
                _allocator.releaseStringBuilder(BufferRecycler.CHAR_TEXT_BUFFER, buf);
            }
        }
    }

    @Override
    public void resetWithString(String valueStr) {
        _inputBuffer = null;
        _inputStart = -1;
        _inputLen = 0;

        _resultString = valueStr;
        _resultArray = null;

        if (_hasSegments) {
            clearSegments();
        }
        if (_currentSegment != null) {
            _currentSegment.setLength(0);
        }
    }

    @Override
    public long contentsAsLong(boolean numberNegative) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int contentsAsInt(boolean numberNegative) {
        throw new UnsupportedOperationException();
    }

    /**
     * Accessor that may be used to get the contents of this buffer as a single
     * {@code char[]} regardless of whether they were collected in a segmented
     * fashion or not: this typically require allocation of the result buffer.
     *
     * @return Aggregated {@code char[]} that contains all buffered content
     */
    public char[] getTextBuffer()
    {
        // Are we just using shared input buffer?
        if (_inputStart >= 0) return _inputBuffer;
        if (_resultArray != null)  return _resultArray;
        if (_resultString != null) {
            return (_resultArray = _resultString.toCharArray());
        }
        // Nope; but does it fit in just one segment?
        if (!_hasSegments) {
            return (_currentSegment == null) ? NO_CHARS : _currentSegment.toString().toCharArray();
        }
        // Nope, need to have/create a non-segmented array and return it
        return contentsAsArray();
    }

    /*
    /**********************************************************
    /* Other accessors:
    /**********************************************************
     */

    /**
     * Accessor that may be used to get the contents of this buffer as a single
     * {@code String} regardless of whether they were collected in a segmented
     * fashion or not: this typically require construction of the result String.
     *
     * @return Aggregated buffered contents as a {@link String}
     */
    public String contentsAsString()
    {
        if (_resultString == null) {
            // Has array been requested? Can make a shortcut, if so:
            if (_resultArray != null) {
                _resultString = new String(_resultArray);
            } else {
                // Do we use shared array?
                if (_inputStart >= 0) {
                    if (_inputLen < 1) {
                        return (_resultString = "");
                    }
                    _resultString = new String(_inputBuffer, _inputStart, _inputLen);
                } else { // nope... need to copy
                    // But first, let's see if we have just one buffer
                    int segLen = _segmentSize;
                    int currLen = _currentSegment.length();

                    if (segLen == 0) { // yup
                        _resultString = (currLen == 0) ? "" : _currentSegment.toString();
                    } else { // no, need to combine
                        StringBuilder sb = new StringBuilder(segLen + currLen);
                        // First stored segments
                        if (_segments != null) {
                            for (int i = 0, len = _segments.size(); i < len; ++i) {
                                StringBuilder curr = _segments.get(i);
                                sb.append(curr);
                            }
                        }
                        // And finally, current segment:
                        sb.append(_currentSegment);
                        _resultString = sb.toString();
                    }
                }
            }
        }

        return _resultString;
    }

    public char[] contentsAsArray() {
        char[] result = _resultArray;
        if (result == null) {
            _resultArray = result = resultArray();
        }
        return result;
    }

    /**
     * Convenience method for converting contents of the buffer
     * into a Double value.
     *
     * @param useFastParser whether to use {@code FastDoubleParser}
     * @return Buffered text value parsed as a {@link Double}, if possible
     *
     * @throws NumberFormatException if contents are not a valid Java number
     *
     * @since 2.14
     */
    public double contentsAsDouble(final boolean useFastParser) throws NumberFormatException {
        return NumberInput.parseDouble(contentsAsString(), useFastParser);
    }

    @Deprecated // @since 2.14
    public double contentsAsDouble() throws NumberFormatException {
        return contentsAsDouble(false);
    }

    @Deprecated // @since 2.14
    public float contentsAsFloat() throws NumberFormatException {
        return contentsAsFloat(false);
    }

    /**
     * Convenience method for converting contents of the buffer
     * into a Float value.
     *
     * @param useFastParser whether to use {@code FastDoubleParser}
     * @return Buffered text value parsed as a {@link Float}, if possible
     *
     * @throws NumberFormatException if contents are not a valid Java number
     * @since 2.14
     */
    public float contentsAsFloat(boolean useFastParser) throws NumberFormatException {
        final String numStr = contentsAsString();
        return NumberInput.parseFloat(numStr, useFastParser);
    }

    /**
     * @return Buffered text value parsed as a {@link BigDecimal}, if possible
     *
     * @deprecated Since 2.15 just access String contents if necessary, call
     *   {@link NumberInput#parseBigDecimal(String, boolean)} (or other overloads)
     *   directly instead
     */
    @Deprecated
    public BigDecimal contentsAsDecimal() throws NumberFormatException {
        // Was more optimized earlier, removing special handling due to deprecation
        return NumberInput.parseBigDecimal(contentsAsArray());
    }

    /**
     * Accessor that will write buffered contents using given {@link Writer}.
     *
     * @param w Writer to use for writing out buffered content
     *
     * @return Number of characters written (same as {@link #size()})
     *
     * @throws IOException If write using {@link Writer} parameter fails
     *
     * @since 2.8
     */
    public int contentsToWriter(Writer w) throws IOException
    {
        if (_resultArray != null) {
            w.write(_resultArray);
            return _resultArray.length;
        }
        if (_resultString != null) { // Can take a shortcut...
            w.write(_resultString);
            return _resultString.length();
        }
        // Do we use shared array?
        if (_inputStart >= 0) {
            final int len = _inputLen;
            if (len > 0) {
                w.write(_inputBuffer, _inputStart, len);
            }
            return len;
        }
        // nope, not shared
        int total = 0;
        if (_segments != null) {
            for (int i = 0, end = _segments.size(); i < end; ++i) {
                StringBuilder curr = _segments.get(i);
                int currLen = curr.length();
                w.write(curr.toString());
                total += currLen;
            }
        }
        int len = _currentSegment.length();
        if (len > 0) {
            w.write(_currentSegment.toString());
            total += len;
        }
        return total;
    }

    /*
    /**********************************************************
    /* Public mutators:
    /**********************************************************
     */

    public void append(char c) {
        // Using shared buffer so far?
        if (_inputStart >= 0) {
            unshare(16);
        }
        _resultString = null;
        _resultArray = null;
        // Room in current segment?
        StringBuilder curr = _currentSegment;
        if (curr.length() >= curr.capacity()) {
            expand();
            curr = _currentSegment;
        }
        curr.append(c);
    }

    public void append(char[] c, int start, int len)
    {
        // Can't append to shared buf (sanity check)
        if (_inputStart >= 0) {
            unshare(len);
        }
        _resultString = null;
        _resultArray = null;

        // Room in current segment?
        StringBuilder curr = _currentSegment;
        int max = curr.capacity() - curr.length();

        if (max >= len) {
            curr.append(c, start, len);
            return;
        }
        // No room for all, need to copy part(s):
        if (max > 0) {
            curr.append(c, start, max);
            start += max;
            len -= max;
        }
        // And then allocate new segment; we are guaranteed to now
        // have enough room in segment.
        do {
            expand();
            int amount = Math.min(_currentSegment.capacity(), len);
            _currentSegment.append(c, start, amount);
            start += amount;
            len -= amount;
        } while (len > 0);
    }

    public void append(String str, int offset, int len)
    {
        // Can't append to shared buf (sanity check)
        if (_inputStart >= 0) {
            unshare(len);
        }
        _resultString = null;
        _resultArray = null;

        // Room in current segment?
        StringBuilder curr = _currentSegment;
        int max = curr.capacity() - curr.length();
        if (max >= len) {
            curr.append(str, offset, offset+len);
            return;
        }
        // No room for all, need to copy part(s):
        if (max > 0) {
            curr.append(str, offset, offset+max);
            len -= max;
            offset += max;
        }
        // And then allocate new segment; we are guaranteed to now
        // have enough room in segment.
        do {
            expand();
            int amount = Math.min(_currentSegment.capacity(), len);
            _currentSegment.append(str, offset, offset+amount);
            offset += amount;
            len -= amount;
        } while (len > 0);
    }

    /*
    /**********************************************************
    /* Raw access, for high-performance use:
    /**********************************************************
     */

    public StringBuilder emptyAndGetCurrentSegment()
    {
        // inlined 'resetWithEmpty()'
        _inputStart = -1; // indicates shared buffer not used
        if (_currentSegment != null) {
            _currentSegment.setLength(0);
        }
        _inputLen = 0;

        _inputBuffer = null;
        _resultString = null;
        _resultArray = null;

        // And then reset internal input buffers, if necessary:
        if (_hasSegments) {
            clearSegments();
        }
        StringBuilder curr = _currentSegment;
        if (curr == null) {
            _currentSegment = curr = bui(0);
        }
        return curr;
    }

    /**
     * Convenience method that finishes the current active content segment
     * (by specifying how many characters within consists of valid content)
     * and aggregates and returns resulting contents (similar to a call
     * to {@link #contentsAsString()}).
     *
     * @param len Length of content (in characters) of the current active segment
     *
     * @return String that contains all buffered content
     *
     * @since 2.6
     */
    public String setCurrentAndReturn() {
        return contentsAsString();
    }

    public StringBuilder finishCurrentSegment() {
        if (_segments == null) {
            _segments = new ArrayList<>();
        }
        _hasSegments = true;
        _segments.add(_currentSegment);
        int oldLen = _currentSegment.length();
        _segmentSize += oldLen;

        // Let's grow segments by 50%
        int newLen = oldLen + (oldLen >> 1);
        if (newLen < MIN_SEGMENT_LEN) {
            newLen = MIN_SEGMENT_LEN;
        } else if (newLen > MAX_SEGMENT_LEN) {
            newLen = MAX_SEGMENT_LEN;
        }
        StringBuilder curr = new StringBuilder(newLen);
        _currentSegment = curr;
        return curr;
    }

    /**
     * Method called to expand size of the current segment, to
     * accommodate for more contiguous content. Usually only
     * used when parsing tokens like names if even then.
     * Method will both expand the segment and return it
     *
     * @return Expanded current segment
     */
    public StringBuilder expandCurrentSegment()
    {
        final StringBuilder curr = _currentSegment;
        // Let's grow by 50% by default
        final int len = curr.capacity();
        int newLen = len + (len >> 1);
        // but above intended maximum, slow to increase by 25%
        if (newLen > MAX_SEGMENT_LEN) {
            newLen = len + (len >> 2);
        }
        curr.ensureCapacity(newLen);
        return curr;
    }

    /**
     * Method called to expand size of the current segment, to
     * accommodate for more contiguous content. Usually only
     * used when parsing tokens like names if even then.
     *
     * @param minSize Required minimum strength of the current segment
     * @return Expanded current segment
     * @since 2.4
     */
    public StringBuilder expandCurrentSegment(int minSize) {
        StringBuilder curr = _currentSegment;
        if (curr.capacity() >= minSize) return curr;
        curr.ensureCapacity(minSize);
        return curr;
    }

    /*
    /**********************************************************
    /* Standard methods:
    /**********************************************************
     */

    /**
     * Note: calling this method may not be as efficient as calling
     * {@link #contentsAsString}, since it's not guaranteed that resulting
     * String is cached.
     */
    @Override public String toString() { return contentsAsString(); }

    /*
    /**********************************************************
    /* Internal methods:
    /**********************************************************
     */

    /**
     * Method called if/when we need to append content when we have been
     * initialized to use shared buffer.
     */
    private void unshare(int needExtra)
    {
        int sharedLen = _inputLen;
        _inputLen = 0;
        char[] inputBuf = _inputBuffer;
        _inputBuffer = null;
        int start = _inputStart;
        _inputStart = -1;

        // Is buffer big enough, or do we need to reallocate?
        int needed = sharedLen+needExtra;
        if (_currentSegment == null || needed > _currentSegment.capacity()) {
            _currentSegment = bui(needed);
        }
        if (sharedLen > 0) {
            _currentSegment.append(inputBuf, start, sharedLen);
        }
        _segmentSize = 0;
    }

    // Method called when current segment is full, to allocate new segment.
    private void expand()
    {
        // First, let's move current segment to segment list:
        if (_segments == null) {
            _segments = new ArrayList<>();
        }
        StringBuilder curr = _currentSegment;
        _hasSegments = true;
        _segments.add(curr);
        _segmentSize += curr.length();
        int oldLen = curr.length();

        // Let's grow segments by 50% minimum
        int newLen = oldLen + (oldLen >> 1);
        if (newLen < MIN_SEGMENT_LEN) {
            newLen = MIN_SEGMENT_LEN;
        } else if (newLen > MAX_SEGMENT_LEN) {
            newLen = MAX_SEGMENT_LEN;
        }
        _currentSegment = new StringBuilder(newLen);
    }

    private char[] resultArray()
    {
        if (_resultString != null) { // Can take a shortcut...
            return _resultString.toCharArray();
        }
        // Do we use shared array?
        if (_inputStart >= 0) {
            final int len = _inputLen;
            if (len < 1) {
                return NO_CHARS;
            }
            final int start = _inputStart;
            if (start == 0) {
                return Arrays.copyOf(_inputBuffer, len);
            }
            return Arrays.copyOfRange(_inputBuffer, start, start+len);
        }
        // nope, not shared
        int size = size();
        if (size < 1) {
            return NO_CHARS;
        }
        int offset = 0;
        final char[] result = carr(size);
        if (_segments != null) {
            for (int i = 0, len = _segments.size(); i < len; ++i) {
                StringBuilder curr = _segments.get(i);
                int currLen = curr.length();
                curr.getChars(0, currLen, result, offset);
                offset += currLen;
            }
        }
        _currentSegment.getChars(0, _currentSegment.length(), result, offset);
        return result;
    }

    private char[] carr(int len) { return new char[len]; }
}
