package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.llm.LlmClient;
import java.util.List;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;

/** 设置页：LLM 服务配置表单 + 连接测试（FR-7）。 */
public class SettingsController {
  private final AppState state;

  @FXML private TextField baseUrl;
  @FXML private TextField embedBaseUrl;
  @FXML private TextField apiKey;
  @FXML private TextField chatModel;
  @FXML private TextField embedModel;
  @FXML private TextField vectorDim;
  @FXML private TextField autoThreshold;
  @FXML private TextField pendingThreshold;
  @FXML private TextField concurrency;

  public SettingsController(AppState state) {
    this.state = state;
  }

  @FXML
  private void initialize() {
    GlobalConfig c = state.config();
    baseUrl.setText(c.baseUrl());
    embedBaseUrl.setText(c.embedBaseUrl());
    apiKey.setText(c.apiKey());
    chatModel.setText(c.chatModel());
    embedModel.setText(c.embedModel());
    vectorDim.setText(String.valueOf(c.vectorDim()));
    autoThreshold.setText(String.valueOf(c.autoThreshold()));
    pendingThreshold.setText(String.valueOf(c.pendingThreshold()));
    concurrency.setText(String.valueOf(c.concurrency()));
  }

  @FXML
  private void onSave() {
    try {
      state.saveConfig(
          new GlobalConfig(
              baseUrl.getText().strip(),
              embedBaseUrl.getText().strip(),
              apiKey.getText().strip(),
              chatModel.getText().strip(),
              embedModel.getText().strip(),
              Integer.parseInt(vectorDim.getText().strip()),
              Double.parseDouble(autoThreshold.getText().strip()),
              Double.parseDouble(pendingThreshold.getText().strip()),
              Integer.parseInt(concurrency.getText().strip())));
      Fx.alert("设置已保存");
    } catch (Exception e) {
      Fx.error("保存失败（检查数字格式）", e);
    }
  }

  /** 连接测试：对话与向量两个端点分别探测并分别报告（chat 挂而 embed 通时不再误报「连接正常」）。 */
  @FXML
  private void onTestConnection() {
    Fx.runAsync(
        () -> {
          String chat;
          try {
            state.llm().chat(List.of(LlmClient.Msg.user("连接测试，请只回复：OK")));
            chat = "正常";
          } catch (Exception e) {
            chat = "失败：" + rootMessage(e);
          }
          String embed;
          try {
            embed = "正常（维度 " + state.llm().embed("连接测试").length + "）";
          } catch (Exception e) {
            embed = "失败：" + rootMessage(e);
          }
          return new String[] {chat, embed};
        },
        r -> Fx.alert("连接测试", "对话服务：" + r[0] + "\n向量服务：" + r[1]),
        t -> Fx.error("连接测试失败", t));
  }

  private static String rootMessage(Throwable t) {
    Throwable cur = t;
    while (cur.getCause() != null && cur.getCause() != cur) {
      cur = cur.getCause();
    }
    String m = cur.getMessage();
    return m == null || m.isBlank() ? cur.getClass().getSimpleName() : m;
  }
}
