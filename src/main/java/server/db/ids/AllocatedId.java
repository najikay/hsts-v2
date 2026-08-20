package server.db.ids;

/**
 * A freshly allocated serial and the display id built from it (E2.14, S-8/S-10).
 *
 * <p>Both halves are returned because both are stored: the schema keeps the serial as a
 * number so the next allocation can find the maximum, and the display id as the padded
 * string users type and read. Deriving one from the other at every call site is how they
 * drift apart.
 *
 * @param serial    the next serial for the course, starting at 1
 * @param displayId the padded, human-facing id
 */
public record AllocatedId(int serial, String displayId) {
}
