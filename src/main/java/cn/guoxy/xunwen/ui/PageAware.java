package cn.guoxy.xunwen.ui;

/** 页面显示后钩子：控制器可感知「页面被切换到」（导航中枢跳转/重复点击同页）。 */
public interface PageAware {
  /** 页面每次成为当前页时回调（FX 线程）；首次加载时 initialize 之后也会调用一次。 */
  default void onPageShown() {}
}
