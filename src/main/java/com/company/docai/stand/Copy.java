package com.company.docai.stand;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A visitor's copy of the demo data.
 *
 * @param key        the key kept in the browser's cookie
 * @param language   the language of the template the copy was made from
 * @param created    when the copy was registered
 * @param lastAccess when a request last came from the browser
 */
public record Copy(String key, String language, Instant created, AtomicReference<Instant> lastAccess) {
}
