package es.gamingtroop.reader

class ReaderTestRunner: androidx.test.runner.AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: android.content.Context): android.app.Application {
        // Tests use fixture feeds only. Never fetch/install a public release during a test run.
        ReaderApp.updateAutomationEnabled = false
        return super.newApplication(cl, className, context)
    }
}
