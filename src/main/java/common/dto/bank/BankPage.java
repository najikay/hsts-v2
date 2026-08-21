package common.dto.bank;

import java.io.Serializable;
import java.util.List;

/**
 * The answer to {@code BANK_LIST}: one page of rows plus what it is a page of (Common tier,
 * E6.5).
 *
 * <p>The paging numbers travel with the rows for the same reason the unread count travels with
 * a notification list: a footer reading "41 to 80 of 312" and the table above it are two views
 * of one truth, and a client deriving the totals from the rows it happens to hold would drift
 * the moment somebody else added a question.
 *
 * <h2>{@code pageSize}, not {@code size}</h2>
 *
 * <p>Deliberately a different name from {@code NotificationsPage.size()}, which already means
 * "rows actually returned". One word meaning two things across two contracts is a bug waiting
 * for the client author who reads the second one first. Here {@code pageSize} is the page the
 * server decided to serve (the request's {@code size}, clamped), and the count of rows in hand
 * is {@link #rowCount()}, which is smaller on the last page.
 *
 * @param rows       the questions on this page; never {@code null}, defensively copied
 * @param page       zero-based index of this page
 * @param pageSize   rows per page the server used, after clamping the request
 * @param totalRows  how many questions match the filters across every page
 * @param totalPages how many pages that makes, so a pager does not divide and round
 */
public record BankPage(List<BankQuestionRow> rows,
                       int page,
                       int pageSize,
                       long totalRows,
                       int totalPages) implements Serializable {

    private static final long serialVersionUID = 1L;

    public BankPage {
        // List.copyOf yields an immutable, Serializable list - safe on the wire.
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * @param pageSize the page size the server used
     * @return the first page of a bank with nothing in it, which is an empty state to draw
     *         rather than an error
     */
    public static BankPage empty(int pageSize) {
        return new BankPage(List.of(), 0, pageSize, 0L, 0);
    }

    /** @return how many rows are actually in hand, which is less than {@link #pageSize()} on
     *          the last page. */
    public int rowCount() {
        return rows.size();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** @return whether another page exists after this one. */
    public boolean hasNextPage() {
        return page + 1 < totalPages;
    }
}
