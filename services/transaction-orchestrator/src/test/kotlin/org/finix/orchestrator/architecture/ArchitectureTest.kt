package org.finix.orchestrator.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.finix.kernel.test.HexagonalArchitecture
import org.junit.jupiter.api.Test

class ArchitectureTest {

    @Test
    fun `hexagonal boundaries hold`() {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("org.finix.orchestrator")
        HexagonalArchitecture.assertAll(classes, "org.finix.orchestrator")
    }
}
