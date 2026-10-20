package cn.guoxy.xunwen.repo;

import java.util.List;

public record Category(String name, String description, List<Category> children) {
  public Category {
    children = children == null ? List.of() : List.copyOf(children);
  }
}
