package org.finix.loan.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.core.spec.style.StringSpec
import org.finix.kernel.test.HexagonalArchitecture

class HexagonalArchitectureTest : StringSpec({

    "hexagon boundaries hold" {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("org.finix.loan")
        HexagonalArchitecture.assertAll(classes, "org.finix.loan")
    }
})
