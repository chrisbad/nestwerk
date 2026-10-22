package club.badernet.nestwerk.ai;

import club.badernet.nestwerk.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the rule "every feature is controllable via MCP": every web action that deletes something has a matching MCP
 * tool or a documented exception (CLAUDE.md, "Exceptions for MCP"). A new delete button without a tool fails here.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "nestwerk.admin.password=test-admin-pw")
class McpCoverageTest {

    /** Last path segments that remove, drop or cancel something. */
    private static final Pattern DELETE_ACTION = Pattern.compile(".*/(delete|remove|cancel|revoke)(/.*)?$");

    /** Web delete action (path pattern without the controller-specific variable names) to the tool doing the same. */
    private static final Map<String, String> TOOLS = new TreeMap<>(Map.ofEntries(
            Map.entry("/admin/members/{id}/delete", "delete_member"),
            Map.entry("/admin/roles/{id}/delete", "delete_role"),
            Map.entry("/attachments/{id}/delete", "delete_attachment"),
            Map.entry("/calendar/actions/chores/{id}/cancel", "cancel_chore_task"),
            Map.entry("/calendar/actions/chores/templates/{choreId}/delete", "delete_chore"),
            Map.entry("/calendar/actions/events/{id}/delete", "delete_event"),
            Map.entry("/calendar/actions/events/{id}/occurrence/cancel", "cancel_event_occurrence"),
            Map.entry("/calendar/actions/todos/{id}/delete", "delete_todo"),
            Map.entry("/calendar/categories/{id}/delete", "delete_event_category"),
            Map.entry("/calendar/events/{id}/delete", "delete_event"),
            Map.entry("/calendar/events/{id}/occurrence/cancel", "cancel_event_occurrence"),
            Map.entry("/contacts/{id}/delete", "delete_contact"),
            Map.entry("/household/chores/{id}/dates/{dateId}/delete", "remove_chore_date"),
            Map.entry("/household/chores/{id}/delete", "delete_chore"),
            Map.entry("/household/rewards/{id}/delete", "delete_reward"),
            Map.entry("/kitchen/foods/{id}/delete", "delete_food"),
            Map.entry("/kitchen/pantry/{id}/delete", "delete_pantry_item"),
            Map.entry("/kitchen/plan/meals/{id}/delete", "delete_meal"),
            Map.entry("/kitchen/recipes/{id}/delete", "delete_recipe"),
            Map.entry("/kitchen/settings/slots/{id}/delete", "delete_meal_slot"),
            Map.entry("/kitchen/shopping/items/{id}/delete", "delete_shopping_item"),
            Map.entry("/school/changes/{id}/delete", "clear_lesson_change"),
            Map.entry("/school/exams/{id}/delete", "delete_school_exam"),
            Map.entry("/school/grades/{id}/delete", "delete_school_grade"),
            Map.entry("/school/holidays/{id}/delete", "delete_school_holiday"),
            Map.entry("/school/lessons/{id}/delete", "delete_school_lesson"),
            Map.entry("/school/letters/{id}/delete", "delete_school_letter"),
            Map.entry("/school/subjects/{id}/delete", "delete_school_subject"),
            Map.entry("/times/goals/{id}/delete", "delete_rest_goal"),
            Map.entry("/times/{id}/delete", "delete_blocker"),
            Map.entry("/todos/{id}/comments/{commentId}/delete", "delete_todo_comment"),
            Map.entry("/todos/{id}/delete", "delete_todo"),
            Map.entry("/todos/{id}/links/{linkId}/delete", "unlink_todos"),
            Map.entry("/todos/{id}/watchers/{userId}/delete", "watch_todo")));

    /** Delete actions that stay web-only on purpose (security-critical administration), with the reason. */
    private static final Map<String, String> EXEMPT = new TreeMap<>(Map.ofEntries(
            Map.entry("/admin/backup/files/{name}/delete", "Backup files are restored and deleted in the web UI only; "
                    + "MCP keeps status, schedule, retention and start"),
            Map.entry("/admin/devices/{id}/revoke", "Tablet devices and their PINs are managed in the web UI only"),
            Map.entry("/settings/ai/{id}/revoke", "AI access tokens are issued and revoked in the web UI only"),
            Map.entry("/settings/notifications/push-subscriptions/{id}/delete",
                    "Push subscriptions belong to a browser and are managed there")));

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private Set<String> deleteActions() {
        return handlerMapping.getHandlerMethods().keySet().stream()
                .filter(info -> info.getMethodsCondition().getMethods().contains(RequestMethod.POST))
                .flatMap(info -> patterns(info).stream())
                .filter(path -> DELETE_ACTION.matcher(path).matches())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> patterns(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null ? Set.of() : info.getPathPatternsCondition().getPatternValues();
    }

    private static Set<String> toolNames() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Component.class));
        return scanner.findCandidateComponents("club.badernet.nestwerk.ai.tools").stream().map(def -> {
            try {
                return Class.forName(def.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }).flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .map(method -> method.getAnnotation(McpTool.class)).filter(java.util.Objects::nonNull)
                .map(McpTool::name).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyWebDeleteActionHasAToolOrADocumentedException() {
        Set<String> missing = new TreeSet<>(deleteActions());
        missing.removeAll(TOOLS.keySet());
        missing.removeAll(EXEMPT.keySet());

        assertThat(missing)
                .as("web delete actions without an MCP tool: add the tool and list it in TOOLS, or document an "
                        + "exception in EXEMPT and in CLAUDE.md")
                .isEmpty();
    }

    @Test
    void theListsOnlyContainRealActionsAndRealTools() {
        Set<String> actions = deleteActions();
        Set<String> tools = toolNames();

        assertThat(TOOLS.keySet()).as("TOOLS lists actions that no longer exist").isSubsetOf(actions);
        assertThat(EXEMPT.keySet()).as("EXEMPT lists actions that no longer exist").isSubsetOf(actions);
        assertThat(TOOLS.values()).as("TOOLS names tools that do not exist").isSubsetOf(tools);
        assertThat(EXEMPT.values()).as("every exception needs a reason").allMatch(reason -> !reason.isBlank());
        assertThat(TOOLS.keySet()).as("an action is either covered or exempt").doesNotContainAnyElementsOf(
                EXEMPT.keySet());
    }

    @Test
    void deleteToolsAreMarkedDestructive() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Component.class));
        Set<String> notMarked = scanner.findCandidateComponents("club.badernet.nestwerk.ai.tools").stream()
                .map(def -> {
                    try {
                        return Class.forName(def.getBeanClassName());
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                }).flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .map(method -> method.getAnnotation(McpTool.class)).filter(java.util.Objects::nonNull)
                .filter(tool -> tool.name().startsWith("delete_") && !tool.annotations().destructiveHint())
                .map(McpTool::name).collect(Collectors.toCollection(TreeSet::new));

        assertThat(notMarked).as("delete tools must set destructiveHint = true").isEmpty();
    }
}
