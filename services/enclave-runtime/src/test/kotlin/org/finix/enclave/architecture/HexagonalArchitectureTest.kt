package org.finix.enclave.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.core.spec.style.StringSpec
import org.finix.kernel.test.HexagonalArchitecture

class HexagonalArchitectureTest : StringSpec({
    "enclave-runtime respects the hexagonal boundary rules" {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("org.finix.enclave")
        HexagonalArchitecture.assertAll(classes, "org.finix.enclave")
    }
})
