package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.pipeline.IngestTask;
import cn.guoxy.xunwen.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

/** 打开仓库的统一入口：未冷启动引导一键建树，否则后台同步积压（仓库页与总览页共用）。 */
final class RepoOpener {
  private RepoOpener() {}

  static void open(AppState state, Path selected) {
    state.currentRepoProperty().set(selected);
    if (!Files.exists(RepoPaths.categoriesYaml(selected))) {
      if (Files.exists(RepoPaths.proposalYaml(selected))) {
        // 已有未生效提案（如应用重启前生成的）：直接跳类别树恢复编辑，不重新生成
        state.requestNavigation("TREE");
        return;
      }
      var guide =
          new Alert(
              Alert.AlertType.CONFIRMATION,
              "该仓库还没有类别树。是否立即采样并生成类别树提案？\n（调用 LLM 归纳分类体系，约需 1 分钟，生成后你审核批准）");
      guide.setTitle("完成冷启动");
      guide.setHeaderText("仓库「" + selected.getFileName() + "」需要先完成冷启动");
      guide
          .showAndWait()
          .ifPresent(
              bt -> {
                if (bt == ButtonType.OK) {
                  state.setAutoGenerateProposal(true);
                  state.requestNavigation("TREE");
                }
              });
      return;
    }
    Fx.ProgressBox progress = Fx.progressBox("正在同步仓库「" + selected.getFileName() + "」…");
    Fx.runEngine(
        state,
        () ->
            new IngestTask(selected, state.config(), state.llm(), state.extractor())
                .sync((done, total, file) -> progress.update(done, total, file)),
        stats -> {
          progress.close();
          state.markDataChanged();
          state.activity("已同步「" + selected.getFileName() + "」，本次：" + stats.summary());
          String report =
              "已打开仓库「"
                  + selected.getFileName()
                  + "」\n\n本次同步："
                  + stats.summary()
                  + (stats.failed() > 0 ? "\n\n失败文件已跳过（LLM 不可达或文件被占用），详见日志，下次同步自动重试。" : "");
          Fx.alert("打开仓库", report);
        },
        t -> {
          progress.close();
          Fx.error("同步失败", t);
        });
  }
}
