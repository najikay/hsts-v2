package server.features.grading;

import server.db.TestDatabase;
import server.db.TestDatabases;

/** Acceptance 8.4's walk on the fast in-memory engine. */
class TeacherCommentFlowH2Test extends TeacherCommentFlowContract {

    @Override
    protected TestDatabase openDatabase() {
        return TestDatabases.h2();
    }
}
