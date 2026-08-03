/**
 * Vditor Markdown 编辑器模块
 * 支持图片粘贴/拖拽自动上传（带可视化进度条和确认对话框）
 */
const VditorEditor = {
    editor: null,
    uploadUrl: '/api/admin/media/upload',
    progressContainer: null,
    
    /**
     * 初始化编辑器
     * @param {string} containerId - 编辑器容器 ID
     * @param {string} initialValue - 初始内容
     */
    init(containerId, initialValue = '') {
        const self = this;
        
        // 创建进度条容器
        this.createProgressContainer();
        
        this.editor = new Vditor(containerId, {
            height: 400,
            mode: 'ir', // 默认使用即时渲染模式（所见即所得）
            theme: 'classic',
            lang: 'zh_CN',
            value: initialValue,
            
            // 使用 WebJars 本地资源，避免从 CDN 加载
            // 所有资源（CSS、JS、图标、数学公式等）都从本地加载
            cdn: '/webjars/vditor',
            
            // 指定各个依赖库的 CDN 路径（使用 WebJars）
            // 这样可以确保所有外部资源都使用本地版本
            cdnData: {
                math: {
                    mathjax: '/webjars/vditor/dist/js/mathjax/tex-mml-chtml.js',
                    katex: '/webjars/vditor/dist/js/katex/katex.min.js'
                },
                mermaid: '/webjars/vditor/dist/js/mermaid/mermaid.min.js',
                markmap: '/webjars/vditor/dist/js/markmap/index.min.js',
                plantuml: '/webjars/vditor/dist/js/plantuml/plantuml-encoder.min.js',
                abcjs: '/webjars/vditor/dist/js/abcjs/abcjs_basic.min.js',
                render: '/webjars/vditor/dist/js/lute/lute.min.js'
            },
            
            // 数学公式配置（使用 KaTeX）
            math: {
                engine: 'KaTeX',
                inlineDigit: false,
                macros: {}
            },
            
            // 图表配置
            chart: {
                enable: true
            },
            
            // 语音配置
            speech: {
                enable: {
                    speak: false,
                    recognize: false
                }
            },
            
            // 工具栏配置
            toolbar: [
                'emoji', 'headings', 'bold', 'italic', 'strike', 'link', '|',
                'list', 'ordered-list', 'check', 'outdent', 'indent', '|',
                'quote', 'line', 'code', 'inline-code', 'insert-before', 'insert-after', '|',
                'upload', 'table', '|',
                'undo', 'redo', '|',
                'edit-mode', 'content-theme', 'code-theme', 'export', '|',
                'fullscreen', 'preview', 'both', 'devtools', '|',
                'help'
            ],
            
            // 图片上传配置
            upload: {
                accept: 'image/*',
                url: this.uploadUrl,
                linkToImg: {
                    enable: true,
                    default: false // 不自动使用链接
                },
                filename(name) {
                    return name.replace(/[^(a-zA-Z0-9\u4e00-\u9fa5\.)]/g, '')
                        .replace(/[\?\\/:|<>\*\[\]\(\)\$%\{\}@~!#&=+\-_,;]/g, '')
                        .replace(/\s/g, '-');
                },
                
                // 自定义上传处理
                handler(files) {
                    return self.handleUploadWithConfirm(files);
                },
                
                // 上传进度回调
                progress(rate) {
                    self.updateProgress(rate);
                }
            },
            
            // 粘贴处理
            paste: {
                paste(event) {
                    // 检测是否有文件被粘贴
                    const items = (event.clipboardData || event.originalEvent.clipboardData).items;
                    const fileItems = [];
                    
                    for (const item of items) {
                        if (item.kind === 'file' && item.type.startsWith('image/')) {
                            fileItems.push(item);
                        }
                    }
                    
                    if (fileItems.length > 0) {
                        event.preventDefault();
                        const files = fileItems.map(item => item.getAsFile());
                        self.handleUploadWithConfirm(files);
                    }
                }
            },
            
            // 拖拽处理
            drag: {
                drop(event) {
                    // Vditor 会自动处理拖拽，我们只需要确保进度条可见
                }
            },
            
            // 计数器
            counter: {
                enable: true,
                type: 'markdown'
            },
            
            // 主题配置
            theme: {
                current: 'classic'
            },
            
            // 预览配置
            preview: {
                theme: {
                    current: 'classic'
                }
            },
            
            // 调试模式
            debugger: false,
            
            // 缓存配置
            cache: {
                enable: false
            }
        });
        
        return this.editor;
    },
    
    /**
     * 创建进度条容器
     */
    createProgressContainer() {
        // 如果已存在则不重复创建
        if (document.getElementById('vditor-progress-container')) {
            return;
        }
        
        const container = document.createElement('div');
        container.id = 'vditor-progress-container';
        container.className = 'fixed top-4 right-4 z-50 w-80';
        container.innerHTML = `
            <div class="bg-white rounded-lg shadow-lg p-4 border border-gray-200">
                <div class="flex justify-between items-center mb-2">
                    <span class="text-sm font-medium text-gray-700">文件上传中...</span>
                    <span id="vditor-progress-percent" class="text-sm font-bold text-blue-600">0%</span>
                </div>
                <div class="w-full bg-gray-200 rounded-full h-2.5">
                    <div id="vditor-progress-bar" class="bg-blue-600 h-2.5 rounded-full transition-all duration-300 vditor-progress-bar"></div>
                </div>
            </div>
        `;
        container.style.display = 'none';
        document.body.appendChild(container);
        this.progressContainer = container;
    },
    
    /**
     * 显示进度条
     */
    showProgress() {
        if (this.progressContainer) {
            this.progressContainer.style.display = 'block';
        }
    },
    
    /**
     * 隐藏进度条
     */
    hideProgress() {
        if (this.progressContainer) {
            setTimeout(() => {
                this.progressContainer.style.display = 'none';
                this.updateProgress(0);
            }, 1000);
        }
    },
    
    /**
     * 更新上传进度
     * @param {number} percent - 进度百分比
     */
    updateProgress(percent) {
        const progressBar = document.getElementById('vditor-progress-bar');
        const progressPercent = document.getElementById('vditor-progress-percent');
        
        if (progressBar && progressPercent) {
            progressBar.style.width = `${percent}%`;
            progressPercent.textContent = `${Math.round(percent)}%`;
        }
        
        console.log(`上传进度：${percent.toFixed(1)}%`);
    },
    
    /**
     * 处理文件上传（带确认对话框）
     * @param {FileList} files - 文件列表
     */
    async handleUploadWithConfirm(files) {
        const self = this;
        
        if (!files || files.length === 0) {
            return [];
        }
        
        // 为每个文件显示确认对话框
        const results = [];
        for (const file of files) {
            try {
                const action = await this.showConfirmDialog(file);
                
                switch (action) {
                    case 'upload_insert':
                        // 上传并插入
                        const result1 = await this.uploadFile(file, true);
                        if (result1) {
                            results.push(result1);
                        }
                        break;
                    
                    case 'upload_only':
                        // 仅上传
                        const result2 = await this.uploadFile(file, false);
                        if (result2) {
                            AdminUtils.showToast(`文件已上传：${file.name}`, 'success');
                            results.push(result2);
                        }
                        break;
                    
                    case 'use_external':
                        // 直接引用外部地址（不上传）
                        const externalUrl = await this.promptExternalUrl(file);
                        if (externalUrl) {
                            this.insertImage(externalUrl, file.name);
                            results.push({
                                msg: '使用外部链接',
                                code: 0,
                                data: {
                                    errFiles: [],
                                    succMap: {
                                        [file.name]: {
                                            err: '',
                                            url: externalUrl,
                                            alt: file.name
                                        }
                                    }
                                }
                            });
                        }
                        break;
                    
                    case 'cancel':
                        // 取消
                        console.log('取消上传:', file.name);
                        break;
                }
            } catch (error) {
                console.error('上传失败:', error);
                AdminUtils.showToast(`图片上传失败：${error.message}`, 'error');
            }
        }
        
        return results;
    },
    
    /**
     * 显示确认对话框
     * @param {File} file - 文件对象
     * @returns {Promise<string>} - 用户选择的操作
     */
    showConfirmDialog(file) {
        return new Promise((resolve) => {
            const modal = document.createElement('div');
            modal.className = 'fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50';
            modal.innerHTML = `
                <div class="bg-white rounded-lg p-6 max-w-md mx-4 shadow-xl">
                    <div class="text-center mb-4">
                        <div class="w-16 h-16 bg-blue-100 rounded-full flex items-center justify-center mx-auto mb-3">
                            <svg class="w-8 h-8 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"></path>
                            </svg>
                        </div>
                        <h3 class="text-lg font-semibold text-gray-800 mb-2">检测到粘贴图片</h3>
                        <p class="text-sm text-gray-600 mb-1">文件名：${file.name}</p>
                        <p class="text-xs text-gray-500">大小：${this.formatFileSize(file.size)}</p>
                    </div>
                    
                    <div class="space-y-2">
                        <button class="upload-insert-btn w-full bg-blue-600 text-white py-2 px-4 rounded-md hover:bg-blue-700 transition">
                            📤 上传并插入到当前位置
                        </button>
                        <button class="upload-only-btn w-full bg-green-600 text-white py-2 px-4 rounded-md hover:bg-green-700 transition">
                            ☁️ 仅上传到媒体库
                        </button>
                        <button class="use-external-btn w-full bg-purple-600 text-white py-2 px-4 rounded-md hover:bg-purple-700 transition">
                            🔗 直接引用外部地址
                        </button>
                        <button class="cancel-btn w-full bg-gray-200 text-gray-700 py-2 px-4 rounded-md hover:bg-gray-300 transition">
                            ❌ 取消插入
                        </button>
                    </div>
                </div>
            `;
            
            document.body.appendChild(modal);
            
            // 绑定按钮事件
            modal.querySelector('.upload-insert-btn').addEventListener('click', () => {
                modal.remove();
                resolve('upload_insert');
            });
            
            modal.querySelector('.upload-only-btn').addEventListener('click', () => {
                modal.remove();
                resolve('upload_only');
            });
            
            modal.querySelector('.use-external-btn').addEventListener('click', () => {
                modal.remove();
                resolve('use_external');
            });
            
            modal.querySelector('.cancel-btn').addEventListener('click', () => {
                modal.remove();
                resolve('cancel');
            });
        });
    },
    
    /**
     * 提示输入外部 URL
     * @param {File} file - 文件对象
     * @returns {Promise<string>} - 外部 URL
     */
    async promptExternalUrl(file) {
        return new Promise((resolve) => {
            const modal = document.createElement('div');
            modal.className = 'fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50';
            modal.innerHTML = `
                <div class="bg-white rounded-lg p-6 max-w-md mx-4 shadow-xl">
                    <h3 class="text-lg font-semibold text-gray-800 mb-4">输入外部图片地址</h3>
                    <input type="text" class="external-url-input w-full px-4 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none mb-4" 
                           placeholder="https://example.com/image.jpg" 
                           value="${file.name}">
                    <div class="flex gap-3">
                        <button class="confirm-btn flex-1 bg-blue-600 text-white py-2 px-4 rounded-md hover:bg-blue-700 transition">确定</button>
                        <button class="cancel-btn flex-1 bg-gray-200 text-gray-700 py-2 px-4 rounded-md hover:bg-gray-300 transition">取消</button>
                    </div>
                </div>
            `;
            
            document.body.appendChild(modal);
            
            const input = modal.querySelector('.external-url-input');
            input.focus();
            input.select();
            
            modal.querySelector('.confirm-btn').addEventListener('click', () => {
                const url = input.value.trim();
                if (url) {
                    modal.remove();
                    resolve(url);
                } else {
                    AdminUtils.showToast('请输入有效的 URL', 'warning');
                }
            });
            
            modal.querySelector('.cancel-btn').addEventListener('click', () => {
                modal.remove();
                resolve(null);
            });
            
            input.addEventListener('keypress', (e) => {
                if (e.key === 'Enter') {
                    modal.querySelector('.confirm-btn').click();
                }
            });
        });
    },
    
    /**
     * 上传单个文件
     * @param {File} file - 文件对象
     * @param {boolean} insertAfterUpload - 上传后是否插入
     * @returns {Promise<object>} - 上传结果
     */
    async uploadFile(file, insertAfterUpload = true) {
        const token = AdminUtils.getToken();
        const formData = new FormData();
        formData.append('file', file);
        
        // 显示进度条
        this.showProgress();
        
        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            
            // 监听上传进度
            xhr.upload.addEventListener('progress', (e) => {
                if (e.lengthComputable) {
                    const percentComplete = (e.loaded / e.total) * 100;
                    this.updateProgress(percentComplete);
                }
            });
            
            // 上传完成
            xhr.addEventListener('load', () => {
                this.hideProgress();
                
                if (xhr.status === 200) {
                    try {
                        const response = JSON.parse(xhr.responseText);
                        const imageUrl = response.url || response.path;
                        const alt = file.name;
                        
                        // 如果需要插入到编辑器
                        if (insertAfterUpload && imageUrl) {
                            this.insertImage(imageUrl, alt);
                        }
                        
                        // 返回 Vditor 需要的格式
                        resolve({
                            msg: '上传成功',
                            code: 0,
                            data: {
                                errFiles: [],
                                succMap: {
                                    [file.name]: {
                                        err: '',
                                        url: imageUrl,
                                        alt: alt
                                    }
                                }
                            }
                        });
                    } catch (e) {
                        reject(new Error('响应解析失败'));
                    }
                } else if (xhr.status === 401) {
                    AdminUtils.showToast('未授权，请重新登录', 'error');
                    reject(new Error('未授权'));
                } else {
                    try {
                        const error = JSON.parse(xhr.responseText);
                        reject(new Error(error.message || `上传失败：${xhr.status}`));
                    } catch (e) {
                        reject(new Error(`上传失败：${xhr.status}`));
                    }
                }
            });
            
            // 上传错误
            xhr.addEventListener('error', () => {
                this.hideProgress();
                reject(new Error('网络错误'));
            });
            
            // 打开请求
            xhr.open('POST', this.uploadUrl);
            xhr.setRequestHeader('Authorization', `Bearer ${token}`);
            
            // 发送请求
            xhr.send(formData);
        });
    },
    
    /**
     * 插入图片到编辑器
     * @param {string} url - 图片 URL
     * @param {string} alt - 图片描述
     */
    insertImage(url, alt = '') {
        if (this.editor) {
            const markdown = `![${alt}](${url})`;
            this.editor.insertValue(markdown);
        }
    },
    
    /**
     * 格式化文件大小
     * @param {number} bytes - 字节数
     * @returns {string} - 格式化后的大小
     */
    formatFileSize(bytes) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
    },
    
    /**
     * 获取编辑器内容
     * @returns {string} Markdown 内容
     */
    getValue() {
        if (this.editor) {
            return this.editor.getValue();
        }
        return '';
    },
    
    /**
     * 设置编辑器内容
     * @param {string} value - Markdown 内容
     */
    setValue(value) {
        if (this.editor && value) {
            this.editor.setValue(value);
        }
    },
    
    /**
     * 获取 HTML 内容
     * @returns {string} HTML 内容
     */
    getHTML() {
        if (this.editor) {
            return this.editor.getHTML();
        }
        return '';
    },
    
    /**
     * 销毁编辑器
     */
    destroy() {
        if (this.editor) {
            this.editor.destroy();
            this.editor = null;
        }
        if (this.progressContainer) {
            this.progressContainer.remove();
            this.progressContainer = null;
        }
    },
    
    /**
     * 设置焦点
     */
    focus() {
        if (this.editor) {
            this.editor.focus();
        }
    },
    
    /**
     * 插入内容
     * @param {string} value - 要插入的内容
     */
    insertValue(value) {
        if (this.editor) {
            this.editor.insertValue(value);
        }
    }
};
