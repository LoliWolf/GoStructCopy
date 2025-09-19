package com.loliwolf.gostructcopy.actions;

import com.loliwolf.gostructcopy.core.GoStructCopyProcessor;
import com.loliwolf.gostructcopy.core.GoStructCopyProcessor.GoStructCopyResult;
import com.goide.psi.GoFile;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;

public class GoStructCopyAction extends AnAction implements DumbAware {
    private static final String NOTIFICATION_GROUP_ID = "GoStructCopy.Notification";

    @Override
    public void update(@NotNull AnActionEvent event) {
        boolean enabled = isGoEditorContext(event);
        event.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
        if (editor == null || !(psiFile instanceof GoFile goFile)) {
            notify(project, "This action is available only inside Go files.", NotificationType.WARNING);
            return;
        }

        GoStructCopyProcessor processor = new GoStructCopyProcessor();
        Computable<GoStructCopyResult> task = () -> processor.expandAtCaret(goFile, editor.getCaretModel().getOffset());
        GoStructCopyResult result = ApplicationManager.getApplication().runReadAction(task);

        if (!result.success()) {
            notify(project, result.message(), NotificationType.WARNING);
            return;
        }

        String content = result.content();
        if (content == null || content.isEmpty()) {
            notify(project, "Nothing to copy.", NotificationType.INFORMATION);
            return;
        }

        CopyPasteManager.getInstance().setContents(new StringSelection(content));
        notify(project, result.message(), NotificationType.INFORMATION);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private static boolean isGoEditorContext(@NotNull AnActionEvent event) {
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
        return editor != null && psiFile instanceof GoFile;
    }

    private static void notify(@NotNull Project project, @NotNull String message, @NotNull NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(message, type)
                .notify(project);
    }
}
