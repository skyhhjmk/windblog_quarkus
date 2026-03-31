/**
 * WindBlog 管理后台通用工具模块
 */
const AdminUtils = {
    API_BASE: '/api/admin',
    
    // Token 管理
    getToken() {
        return localStorage.getItem('admin_token');
    },
    
    setToken(token) {
        localStorage.setItem('admin_token', token);
    },
    
    removeToken() {
        localStorage.removeItem('admin_token');
    },
    
    // 检查是否已登录
    isLoggedIn() {
        return !!this.getToken();
    },
    
    // 验证登录状态
    async checkAuth() {
        const token = this.getToken();
        if (!token) {
            window.location.href = '/admin/login';
            return false;
        }
        
        try {
            const response = await fetch(`${this.API_BASE}/auth/me`, {
                headers: {
                    'Authorization': `Bearer ${token}`
                }
            });
            
            if (!response.ok) {
                this.removeToken();
                window.location.href = '/admin/login';
                return false;
            }
            
            return true;
        } catch (error) {
            this.removeToken();
            window.location.href = '/admin/login';
            return false;
        }
    },
    
    // API 请求封装
    async request(url, options = {}, handle401 = true) {
        const token = this.getToken();
        const headers = {
            'Content-Type': 'application/json',
            ...options.headers
        };
        
        if (token) {
            headers['Authorization'] = `Bearer ${token}`;
        }
        
        const response = await fetch(`${this.API_BASE}${url}`, {
            ...options,
            headers
        });
        
        if (response.status === 401) {
            if (handle401) {
                this.removeToken();
                window.location.href = '/admin/login';
                throw new Error('未授权，请重新登录');
            } else {
                throw new Error('未授权');
            }
        }
        
        const data = await response.json();
        
        if (!response.ok) {
            throw new Error(data.message || '请求失败');
        }
        
        return data;
    },
    
    // GET 请求
    async get(url) {
        return this.request(url, { method: 'GET' });
    },
    
    // POST 请求
    async post(url, body) {
        return this.request(url, {
            method: 'POST',
            body: JSON.stringify(body)
        });
    },
    
    // PUT 请求
    async put(url, body) {
        return this.request(url, {
            method: 'PUT',
            body: JSON.stringify(body)
        });
    },
    
    // DELETE 请求
    async delete(url) {
        return this.request(url, { method: 'DELETE' });
    },
    
    // 显示提示消息
    showToast(message, type = 'info') {
        const colors = {
            success: 'bg-green-500',
            error: 'bg-red-500',
            info: 'bg-blue-500',
            warning: 'bg-yellow-500'
        };
        
        const toast = document.createElement('div');
        toast.className = `${colors[type]} text-white px-6 py-3 rounded-md shadow-lg fixed top-4 right-4 z-50 transition-opacity duration-300`;
        toast.textContent = message;
        
        document.body.appendChild(toast);
        
        setTimeout(() => {
            toast.style.opacity = '0';
            setTimeout(() => toast.remove(), 300);
        }, 3000);
    },
    
    // 格式化日期
    formatDate(dateString) {
        const date = new Date(dateString);
        return date.toLocaleString('zh-CN', {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit'
        });
    },
    
    // 退出登录
    async logout() {
        this.removeToken();
        window.location.href = '/admin/login';
    }
};

// 队列管理 API
const QueueAPI = {
    // 获取所有队列信息
    async getQueues() {
        return AdminUtils.get('/queues');
    },

    // 获取单个队列详情
    async getQueueDetail(queueName) {
        return AdminUtils.get(`/queues/${queueName}`);
    },

    // 推送测试消息
    async publishMessage(queueName, postData) {
        return AdminUtils.post(`/queues/${queueName}/publish`, postData);
    },

    // 生成消息示例
    async generateExample() {
        return AdminUtils.get('/queues/generate-example');
    },

    // 死信消息相关
    async getDeadLetters(processed = null, limit = 50) {
        let url = `/dead-letters?limit=${limit}`;
        if (processed !== null) {
            url += `&processed=${processed}`;
        }
        return AdminUtils.get(url);
    },

    async getDeadLetterStats() {
        return AdminUtils.get('/dead-letters/stats');
    },

    async retryDeadLetter(id) {
        return AdminUtils.post(`/dead-letters/${id}/retry`);
    },

    async retryBatch(limit = 10) {
        return AdminUtils.post(`/dead-letters/retry-batch?limit=${limit}`);
    },

    async dismissDeadLetter(id, note) {
        return AdminUtils.post(`/dead-letters/${id}/dismiss?note=${encodeURIComponent(note || '')}`);
    },

    async deleteDeadLetter(id) {
        return AdminUtils.delete(`/dead-letters/${id}`);
    }
};

// 页面导航管理
const PageNavigator = {
    navigate(page) {
        const pages = ['dashboard', 'posts', 'categories', 'tags', 'comments', 'users', 'media', 'queues'];
        if (pages.includes(page)) {
            window.location.href = `/admin/${page}`;
        }
    },
    
    setActiveNav(page) {
        document.querySelectorAll('.nav-item').forEach(item => {
            item.classList.remove('bg-gray-700', 'text-white');
            item.classList.add('text-gray-300', 'hover:bg-gray-700', 'hover:text-white');
        });
        
        const activeItem = document.getElementById(`${page}-nav`);
        if (activeItem) {
            activeItem.classList.remove('text-gray-300', 'hover:bg-gray-700', 'hover:text-white');
            activeItem.classList.add('bg-gray-700', 'text-white');
        }
    }
};

// 模态框管理
const Modal = {
    open(modalId) {
        const modal = document.getElementById(modalId);
        if (modal) {
            modal.classList.remove('hidden');
            document.body.style.overflow = 'hidden';
        }
    },
    
    close(modalId) {
        const modal = document.getElementById(modalId);
        if (modal) {
            modal.classList.add('hidden');
            document.body.style.overflow = '';
        }
    },
    
    confirm(message, onConfirm) {
        return new Promise((resolve) => {
            const modal = document.createElement('div');
            modal.className = 'fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50';
            modal.innerHTML = `
                <div class="bg-white rounded-lg p-6 max-w-md mx-4">
                    <p class="text-gray-800 mb-4">${message}</p>
                    <div class="flex justify-end gap-3">
                        <button class="cancel-btn px-4 py-2 text-gray-600 hover:bg-gray-100 rounded">取消</button>
                        <button class="confirm-btn px-4 py-2 bg-red-600 text-white rounded hover:bg-red-700">确认</button>
                    </div>
                </div>
            `;
            
            document.body.appendChild(modal);
            
            modal.querySelector('.cancel-btn').addEventListener('click', () => {
                modal.remove();
                resolve(false);
            });
            
            modal.querySelector('.confirm-btn').addEventListener('click', () => {
                modal.remove();
                resolve(true);
                if (onConfirm) onConfirm();
            });
        });
    }
};
