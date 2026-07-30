package org.finix.account

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.finix.kernel.test.HexagonalArchitecture
import org.junit.jupiter.api.Test

class HexagonalArchitectureTest {

    @Test
    fun `hexagon boundaries hold`() {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("org.finix.account")
        HexagonalArchitecture.assertAll(classes, "org.finix.account")
    }
}
