# 任务列表

## 任务 1: 添加数据模型和 API 客户端方法

- [x] 在 models.dart 中添加 Category 和 Tag 相关模型类
    - [x] CategoryItem 类 (id, parentId, slug, name, description, path, createdAt)
    - [x] CategoryCreateRequest/CategoryUpdateRequest 类
    - [x] TagItem 类 (id, slug, name, description, createdAt)
    - [x] TagCreateRequest/TagUpdateRequest 类
- [x] 在 admin_api_client.dart 中添加 API 方法
    - [x] listCategories() 方法
    - [x] createCategory() 方法
    - [x] updateCategory() 方法
    - [x] deleteCategory() 方法
    - [x] listTags() 方法
    - [x] createTag() 方法
    - [x] updateTag() 方法
    - [x] deleteTag() 方法

## 任务 2: 创建分类管理页面

- [x] 创建 categories_page.dart
    - [x] 实现分类树形列表展示
    - [x] 实现创建分类对话框
    - [x] 实现编辑分类对话框
    - [x] 实现删除分类功能(带确认)
    - [x] 处理加载状态和错误处理

## 任务 3: 创建标签管理页面

- [x] 创建 tags_page.dart
    - [x] 实现标签列表展示
    - [x] 实现创建标签对话框
    - [x] 实现编辑标签对话框
    - [x] 实现删除标签功能(带确认)
    - [x] 处理加载状态和错误处理

## 任务 4: 更新导航菜单和主文件

- [x] 修改 home_page.dart
    - [x] 在 NavigationRail 中添加分类和标签导航项
    - [x] 在 _pageForIndex 中添加对应页面路由
- [x] 修改 main.dart
    - [x] 添加新页面的 part 声明

## 任务 5: 增强文章编辑功能

- [ ] 修改 posts_page.dart 中的 PostDialog
    - [ ] 添加分类选择器(下拉树形结构)
    - [ ] 添加标签选择器(多选 Chips)
    - [ ] 更新 PostEditRequest 以包含 categoryId 和 tagIds
    - [ ] 修改保存逻辑以提交分类和标签数据
    - **注意**: 后端 API 目前不支持 categoryId 和 tagIds 字段，需要后端更新后才能完全生效

## 任务 6: 添加国际化文本

- [x] 修改 zh.json
    - [x] 添加分类相关的文本键值
    - [x] 添加标签相关的文本键值
- [x] 修改 en.json
    - [x] 添加分类相关的文本键值
    - [x] 添加标签相关的文本键值

# 任务依赖关系

- 任务 1 是其他所有任务的前置依赖
- 任务 2 和 任务 3 可以并行执行
- 任务 4 依赖于任务 2 和 任务 3
- 任务 5 依赖于任务 1
- 任务 6 可以与其他任务并行
