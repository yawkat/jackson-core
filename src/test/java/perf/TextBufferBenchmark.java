package perf;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.profile.AsyncProfiler;
import org.openjdk.jmh.profile.LinuxPerfAsmProfiler;
import org.openjdk.jmh.profile.LinuxPerfProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

public class TextBufferBenchmark {
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

    public static void main(String[] args) throws Exception {
        if (false) {
            Input input = new Input();
            input.alphabet = Alphabet.ASCII;
            input.length = 10;
            input.init();
            new TextBufferBenchmark().parseBytes(input);
            return;
        }


        /*
Benchmark                       (alphabet)  (length)  Mode  Cnt        Score       Error  Units
TextBufferBenchmark.parseBytes       ASCII        10  avgt    4      110.075 ±     2.639  ns/op
TextBufferBenchmark.parseBytes       ASCII      1000  avgt    4     1332.412 ±    69.348  ns/op
TextBufferBenchmark.parseBytes       ASCII   1000000  avgt    4  1659248.796 ± 65877.219  ns/op
         */
        new Runner(new OptionsBuilder()
                .include(TextBufferBenchmark.class.getSimpleName())
                .forks(1)
                .timeUnit(TimeUnit.NANOSECONDS)
                .mode(Mode.AverageTime)
                .warmupIterations(2)
                .measurementIterations(4)
                //.addProfiler(AsyncProfiler.class, "libPath=/home/yawkat/bin/async-profiler-2.9-linux-x64/build/libasyncProfiler.so;output=flamegraph")
                //.addProfiler(LinuxPerfAsmProfiler.class)
                .build()).run();
    }

    @Benchmark
    public String parseBytes(Input input) throws IOException {
        JsonParser parser = JSON_FACTORY.createParser(input.json);
        if (parser.nextToken() != JsonToken.VALUE_STRING) {
            throw new AssertionError();
        }
        return parser.getText();
    }

    //@Benchmark
    public String parseInputStream(Input input) throws IOException {
        JsonParser parser = JSON_FACTORY.createParser(new ByteArrayInputStream(input.json));
        if (parser.nextToken() != JsonToken.VALUE_STRING) {
            throw new AssertionError();
        }
        return parser.getText();
    }

    @State(Scope.Thread)
    public static class Input {
        @Param({"10", "1000", "1000000"})
        int length;

        @Param({"ASCII", "GERMAN"})
        Alphabet alphabet;

        byte[] json;

        @Setup
        public void init() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (JsonGenerator generator = JSON_FACTORY.createGenerator(baos)) {
                char[] chars = new char[length];
                for (int i = 0; i < length; i++) {
                    chars[i] = alphabet.chars.charAt(ThreadLocalRandom.current().nextInt(alphabet.chars.length()));
                }
                generator.writeString(chars, 0, chars.length);
            }
            json = baos.toByteArray();
        }
    }

    public enum Alphabet {
        ASCII("abcdefghijklmnopqrstuvwxyz"),
        GERMAN("abcdefghijklmnopqrstuvwxyzäöüß");

        final String chars;

        Alphabet(String chars) {
            this.chars = chars;
        }
    }
}
