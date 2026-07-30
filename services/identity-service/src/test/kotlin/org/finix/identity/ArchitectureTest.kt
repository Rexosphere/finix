package org.finix.identity

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.core.spec.style.StringSpec
import org.finix.kernel.test.HexagonalArchitecture

class ArchitectureTest : StringSpec({

    "hexagonal boundaries hold for identity-service" {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("org.finix.identity")
        HexagonalArchitecture.assertAll(classes, "org.finix.identity")
    }
})
