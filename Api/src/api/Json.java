package api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;

/**
 * The one Gson both ends of the wire use, so the server writes exactly what the client
 * reads.
 *
 * <p>The engine's DTOs are records, which Gson 2.10+ builds through their canonical
 * constructor, and they carry nothing Gson cannot handle on its own except
 * {@link Instant}: a trade's {@code createdAt}. Gson would reflect into {@code java.time}
 * for that, which the JDK forbids, so it is written as the ISO text {@code Instant} itself
 * prints and parses. Nulls are written out rather than dropped because some of them mean
 * something ({@code openingPrice} is null for a book that never had one, and a mint's
 * {@code seller} is null), and a reader should see the null rather than a missing field.
 */
public final class Json {

    public static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()     // nothing here is put in a web page; an apostrophe is an apostrophe
            .registerTypeAdapter(Instant.class, new InstantAdapter().nullSafe())
            .create();

    private Json() {
    }

    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            out.value(value.toString());
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            return Instant.parse(in.nextString());
        }
    }
}