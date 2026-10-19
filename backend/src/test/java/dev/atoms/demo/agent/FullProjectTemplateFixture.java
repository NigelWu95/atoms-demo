package dev.atoms.demo.agent;

/** Test-only entrypoint: loads the production Boot JAR catalog without starting the platform. */
public final class FullProjectTemplateFixture {
    public static void main(String[] args) {
        var tree = new FullProjectTemplateCatalog().load();
        System.out.println(FullProjectTemplateCatalog.ID + " " + tree.sha256() + " " + tree.files().size());
    }
}
