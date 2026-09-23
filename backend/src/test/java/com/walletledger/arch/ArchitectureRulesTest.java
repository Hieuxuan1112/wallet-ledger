package com.walletledger.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Rules that encode decisions this project has already paid for. Each one would have caught a
 * real defect, or protects an invariant explained in docs/hoc/KIEN_TRUC_VA_QUYET_DINH.md.
 */
class ArchitectureRulesTest {

    private static JavaClasses production;

    @BeforeAll
    static void importClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.walletledger");
    }

    /**
     * The chokepoint. If anything but the ledger package can change a balance, the guarantee
     * that all money moves through one place is gone — and with it the value of every check
     * inside post().
     */
    @Test
    void onlyTheLedgerMayMutateBalances() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.walletledger.ledger..")
                .should().callMethod(com.walletledger.account.Account.class, "credit", java.math.BigDecimal.class)
                .orShould().callMethod(com.walletledger.account.Account.class, "debit", java.math.BigDecimal.class)
                .because("all money movement must go through LedgerPostingService");

        rule.check(production);
    }

    /**
     * Controllers own HTTP, services own rules. A controller reaching a repository directly is
     * how business logic starts leaking into the web layer.
     */
    @Test
    void controllersDoNotTouchRepositories() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("controllers translate HTTP; services own the rules");

        rule.check(production);
    }

    /**
     * Bug 10: a negative amount reversed the direction of a posting, and every HTTP test stayed
     * green because the DTOs rejected it at the boundary. This rule stops the money path from
     * ever depending on that boundary again.
     */
    @Test
    void theLedgerDoesNotDependOnTheWebLayer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.walletledger.ledger..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "jakarta.validation..")
                .because("the posting primitive must be correct without HTTP validation above it");

        rule.check(production);
    }
}
