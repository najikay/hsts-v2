package common.dto.bank;

import java.io.Serializable;
import java.util.Objects;

/**
 * One exam that is standing in the way of a delete (Common tier, E6.4 — T-2.7).
 *
 * <p>A record rather than a bare name, because the dialog has to let a teacher go and look. The
 * 6-digit display id is how she finds the exam in her own list and how she refers to it when she
 * asks a colleague about it (S-8), so a refusal listing names alone would be a refusal she cannot
 * act on.
 *
 * @param displayId6 the exam's 6-digit display id, as the teacher sees it everywhere else
 * @param name       the exam's name
 */
public record BlockingExam(String displayId6, String name) implements Serializable {

    private static final long serialVersionUID = 1L;

    public BlockingExam {
        Objects.requireNonNull(displayId6, "displayId6");
        Objects.requireNonNull(name, "name");
    }
}
