package club.badernet.nestwerk.demo;

/** Creates the demo data of one feature area through the public domain services. */
interface DemoArea {

    /** Short name used in log messages. */
    String name();

    void seed(DemoContext context);
}
