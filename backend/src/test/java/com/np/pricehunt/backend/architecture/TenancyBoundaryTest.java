package com.np.pricehunt.backend.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.np.pricehunt.backend.auth.CurrentUser;
import com.np.pricehunt.backend.repository.UserProductRepository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.repository.Repository;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The tenancy boundary as a build-time fact (#246). Scoping is application-layer, so "no user-facing
 * code reaches the catalog unscoped" has to be structural rather than disciplinary; each rule below
 * states its own reason. Together they close the loop: the only way to learn who the caller is is
 * {@link CurrentUser}, and whoever learns it can reach data only through the tenancy port.
 *
 * <p>Production classes only: tests seed memberships through the repository and clear the security
 * context on purpose.
 */
@AnalyzeClasses(packages = "com.np.pricehunt.backend", importOptions = ImportOption.DoNotIncludeTests.class)
class TenancyBoundaryTest {

    private static DescribedPredicate<JavaClass> dependOn(Class<?> type) {
        return new DescribedPredicate<>("depend on " + type.getSimpleName()) {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dependency -> dependency.getTargetClass().isAssignableTo(type));
            }
        };
    }

    @ArchTest
    static final ArchRule whoeverKnowsTheCallerUsesOnlyThePort = noClasses()
            .that()
            .resideOutsideOfPackage("..auth..")
            .and(dependOn(CurrentUser.class))
            .should()
            .dependOnClassesThat()
            .areAssignableTo(Repository.class)
            .because("a class that resolves the caller must read and write through tenancy.UserScopedCatalog");

    @ArchTest
    static final ArchRule membershipIsAccessedOnlyByThePort = classes()
            .that(dependOn(UserProductRepository.class))
            .should()
            .resideInAnyPackage("..tenancy..", "..repository..", "..dev..")
            .because("reaching membership at all is the tenancy port's job; the dev seeder is the one exception");

    @ArchTest
    static final ArchRule schedulersNeverReachTheCaller = noClasses()
            .that()
            .resideInAPackage("..scheduler..")
            .should()
            .transitivelyDependOnClassesThat()
            .areAssignableTo(CurrentUser.class)
            .because("scheduled work runs with no principal and refreshes the whole catalog");

    @ArchTest
    static final ArchRule controllersReachDataOnlyThroughServices = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .areAssignableTo(Repository.class)
            .orShould()
            .dependOnClassesThat()
            .areAssignableTo(CurrentUser.class)
            .because(
                    "a controller neither touches data nor resolves the user id; a service does both, through the port");

    @ArchTest
    static final ArchRule onlyAuthReadsTheSecurityContext = noClasses()
            .that()
            .resideOutsideOfPackage("..auth..")
            .should()
            .dependOnClassesThat()
            .areAssignableTo(SecurityContextHolder.class)
            .because("the only way to learn who the caller is must be CurrentUser, or rule 1 has a side door");
}
