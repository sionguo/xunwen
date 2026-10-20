package cn.guoxy.xunwen.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

public record CategoryTree(List<Category> roots) {

  public static final String PENDING_DIR_NAME = "待分类";

  private static final ObjectMapper YAML =
      YAMLMapper.builder(YAMLFactory.builder().build())
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

  public CategoryTree {
    roots = roots == null ? List.of() : List.copyOf(roots);
  }

  public Optional<Category> find(String categoryPath) {
    if (categoryPath == null || categoryPath.isBlank()) {
      return Optional.empty();
    }
    String[] parts = categoryPath.split("/");
    if (parts.length > 2) {
      return Optional.empty(); // 体系最多两级：更深的路径按 UNKNOWN 分流（FR-3）
    }
    Optional<Category> root = roots.stream().filter(c -> c.name().equals(parts[0])).findFirst();
    if (parts.length == 1) {
      return root;
    }
    return root.flatMap(
        r -> r.children().stream().filter(c -> c.name().equals(parts[1])).findFirst());
  }

  public List<String> flattenPaths() {
    List<String> out = new ArrayList<>();
    for (Category root : roots) {
      out.add(root.name());
      for (Category child : root.children()) {
        out.add(root.name() + "/" + child.name());
      }
    }
    return out;
  }

  record YamlRoot(List<Category> categories) {}

  public void save(Path yaml) throws IOException {
    Files.createDirectories(yaml.getParent());
    YAML.writeValue(yaml.toFile(), new YamlRoot(roots));
  }

  public static CategoryTree load(Path yaml) throws IOException {
    YamlRoot root = YAML.readValue(yaml.toFile(), YamlRoot.class);
    return new CategoryTree(root.categories() == null ? List.of() : root.categories());
  }
}
