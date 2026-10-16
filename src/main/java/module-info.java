module cn.guoxy.xunwen {
  requires javafx.controls;
  requires javafx.fxml;

  requires org.controlsfx.controls;
  requires org.kordamp.ikonli.javafx;

  opens cn.guoxy.xunwen to javafx.fxml;
  exports cn.guoxy.xunwen;
}