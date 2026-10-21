package cn.guoxy.xunwen.ui;

import cn.guoxy.xunwen.config.GlobalConfig;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;

/** 设置页：LLM 服务配置表单 + 连接测试（FR-7）。 */
public class SettingsController {
  private final AppState state;

  @FXML private TextField baseUrl;
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

  @FXML
  private void onTestConnection() {
    Fx.runAsync(
        () -> state.llm().embed("连接测试"),
        v -> Fx.alert("连接正常，向量维度=" + v.length),
        t -> Fx.error("连接失败", t));
  }
}
