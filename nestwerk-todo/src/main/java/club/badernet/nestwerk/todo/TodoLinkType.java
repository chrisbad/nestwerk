package club.badernet.nestwerk.todo;

/** Art einer Verknüpfung; Beschriftung je Richtung (vom Quell- bzw. vom Ziel-ToDo aus gesehen). */
public enum TodoLinkType {
    BLOCKS("blockiert", "wird blockiert von"),
    RELATES("verwandt mit", "verwandt mit"),
    DUPLICATES("ist Duplikat von", "hat Duplikat");

    private final String outgoing;
    private final String incoming;

    TodoLinkType(String outgoing, String incoming) {
        this.outgoing = outgoing;
        this.incoming = incoming;
    }

    public String outgoing() {
        return outgoing;
    }

    public String incoming() {
        return incoming;
    }
}
