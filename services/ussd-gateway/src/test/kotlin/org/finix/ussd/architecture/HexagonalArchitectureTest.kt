package org.finix.ussd.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.core.spec.style.StringSpec
import org.finix.kernel.test.HexagonalArchitecture

class HexagonalArchitectureTest : StringSpec({
    "ussd-gateway respects the hexagonal boundary rules" {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("org.finix.ussd")
        HexagonalArchitecture.assertAll(classes, "org.finix.ussd")
    }
})
