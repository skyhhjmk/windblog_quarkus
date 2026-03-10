# 验收检查清单

## 数据模型和 API 客户端

- [x] CategoryItem 模型类正确定义，包含所有字段
- [x] CategoryCreateRequest/CategoryUpdateRequest 模型类正确定义
- [x] TagItem 模型类正确定义，包含所有字段
- [x] TagCreateRequest/TagUpdateRequest 模型类正确定义
- [x] AdminApiClient 包含 listCategories() 方法并能正确获取数据
- [x] AdminApiClient 包含 createCategory() 方法并能成功创建分类
- [x] AdminApiClient 包含 updateCategory() 方法并能成功更新分类
- [x] AdminApiClient 包含 deleteCategory() 方法并能成功删除分类
- [x] AdminApiClient 包含 listTags() 方法并能正确获取数据
- [x] AdminApiClient 包含 createTag() 方法并能成功创建标签
- [x] AdminApiClient 包含 updateTag() 方法并能成功更新标签
- [x] AdminApiClient 包含 deleteTag() 方法并能成功删除标签

## 分类管理页面

- [x] 分类页面可以正确加载并显示分类列表
- [x] 分类以树形结构展示，层级关系清晰可见
- [x] 点击创建按钮弹出创建对话框
- [x] 创建分类时可以输入 slug、名称、描述
- [x] 创建分类时可以选择父分类
- [x] 新分类创建成功后列表自动刷新
- [x] 点击编辑按钮弹出编辑对话框并预填充数据
- [x] 编辑分类后可以成功保存
- [x] 点击删除按钮显示确认对话框
- [x] 确认删除后分类被移除
- [x] 未授权时正确触发登录过期处理

## 标签管理页面

- [x] 标签页面可以正确加载并显示标签列表
- [x] 标签列表显示 slug、名称、描述信息
- [x] 点击创建按钮弹出创建对话框
- [x] 创建标签时可以输入 slug、名称、描述
- [x] 新标签创建成功后列表自动刷新
- [x] 点击编辑按钮弹出编辑对话框并预填充数据
- [x] 编辑标签后可以成功保存
- [x] 点击删除按钮显示确认对话框
- [x] 确认删除后标签被移除
- [x] 未授权时正确触发登录过期处理

## 导航菜单

- [x] HomePage 导航栏显示分类菜单项
- [x] HomePage 导航栏显示标签菜单项
- [x] 点击分类菜单项切换到分类管理页面
- [x] 点击标签菜单项切换到标签管理页面
- [x] 导航栏图标和文本正确显示

## 文章编辑增强

- [ ] 文章编辑对话框显示分类选择器
- [ ] 分类选择器以下拉形式展示树形结构
- [ ] 可以选择一个主分类
- [ ] 文章编辑对话框显示标签选择器
- [ ] 标签选择器支持多选
- [ ] 保存文章时分类和标签数据正确提交
- [ ] 编辑现有文章时正确加载已选的分类和标签
- **说明**: 后端 API 目前不支持 categoryId 和 tagIds 字段，需要后端更新 PostCreateRequest/PostUpdateRequest DTO 和
  AdminPostApiController 后才能完全生效

## 国际化

- [x] zh.json 包含所有新增文本的中文翻译
- [x] en.json 包含所有新增文本的英文翻译
- [x] 界面文本根据语言设置正确显示
