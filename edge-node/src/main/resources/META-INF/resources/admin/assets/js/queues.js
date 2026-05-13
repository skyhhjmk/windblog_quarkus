/**
 * 队列管理页面专用脚本
 */

// 页面初始化函数
async function initPage() {
    // 加载队列状态
    await loadQueueStatus();

    // 加载死信消息
    await loadDeadLetters();

    // 定时刷新队列状态（每 10 秒）
    setInterval(loadQueueStatus, 10000);
}

// 加载队列状态
async function loadQueueStatus() {
    try {
        const result = await QueueAPI.getQueues();

        if (result.success && result.data) {
            result.data.forEach(queue => {
                if (queue.name === 'ai-summary-tasks') {
                    document.getElementById('taskQueueCount').textContent = (queue.messageCount !== undefined ? queue.messageCount : 0);
                    document.getElementById('taskQueueConsumers').textContent = (queue.consumerCount !== undefined ? queue.consumerCount : 0);
                    document.getElementById('taskQueueStatus').textContent = queue.messageCount >= 0 ? '正常' : '异常';
                } else if (queue.name === 'ai-summary-dead-letter') {
                    document.getElementById('dlxQueueCount').textContent = (queue.messageCount !== undefined ? queue.messageCount : 0);
                    document.getElementById('dlxQueueConsumers').textContent = (queue.consumerCount !== undefined ? queue.consumerCount : 0);
                    document.getElementById('dlxQueueStatus').textContent = queue.messageCount >= 0 ? '正常' : '异常';
                }
            });
        }
    } catch (error) {
        console.error('加载队列状态失败:', error);
        AdminUtils.showToast('加载队列状态失败', 'error');
    }
}

// 加载示例消息
async function loadExample() {
    try {
        const result = await QueueAPI.generateExample();
        if (result.success && result.data) {
            document.getElementById('testPostId').value = (result.data.postId !== undefined ? result.data.postId : 1);
            document.getElementById('testPriority').value = (result.data.priority !== undefined ? result.data.priority : 1);
            document.getElementById('testContent').value = (result.data.content !== undefined ? result.data.content : '');
            AdminUtils.showToast('示例已加载', 'success');
        }
    } catch (error) {
        console.error('加载示例失败:', error);
        AdminUtils.showToast('加载示例失败', 'error');
    }
}

// 推送测试消息
async function publishTestMessage() {
    const postId = document.getElementById('testPostId').value;
    const priority = parseInt(document.getElementById('testPriority').value);
    const content = document.getElementById('testContent').value;

    if (!postId) {
        AdminUtils.showToast('请输入文章 ID', 'warning');
        return;
    }

    try {
        const result = await QueueAPI.publishMessage('ai-summary-tasks', {
            postId: parseInt(postId),
            priority: priority,
            content: content
        });

        if (result.success) {
            AdminUtils.showToast('消息已成功推送到队列', 'success');
            // 清空表单
            document.getElementById('testPostId').value = '';
            document.getElementById('testContent').value = '';
            // 刷新队列状态
            await loadQueueStatus();
        }
    } catch (error) {
        console.error('推送消息失败:', error);
        AdminUtils.showToast(error.message || '推送消息失败', 'error');
    }
}

// 加载死信消息
async function loadDeadLetters() {
    const loadingIndicator = document.getElementById('loadingIndicator');
    const emptyState = document.getElementById('emptyState');
    const tableBody = document.getElementById('deadLetterTableBody');

    loadingIndicator.classList.remove('hidden');
    emptyState.classList.add('hidden');
    tableBody.innerHTML = '';

    try {
        const filter = document.getElementById('deadLetterFilter').value;
        const processed = filter === 'all' ? null : (filter === 'processed');

        const result = await QueueAPI.getDeadLetters(processed, 50);

        loadingIndicator.classList.add('hidden');

        if (!result.data || result.data.length === 0) {
            emptyState.classList.remove('hidden');
            return;
        }

        result.data.forEach(message => {
            const row = createDeadLetterRow(message);
            tableBody.appendChild(row);
        });
    } catch (error) {
        console.error('加载死信消息失败:', error);
        loadingIndicator.classList.add('hidden');
        AdminUtils.showToast('加载死信消息失败', 'error');
    }
}

// 创建死信消息行
function createDeadLetterRow(message) {
    const row = document.createElement('tr');

    const statusBadge = message.isProcessed
        ? '<span class="px-2 py-1 bg-gray-100 text-gray-800 rounded text-xs">已处理</span>'
        : '<span class="px-2 py-1 bg-red-100 text-red-800 rounded text-xs">未处理</span>';

    row.innerHTML = `
        <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-900">${message.id}</td>
        <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-900">${message.postId || '-'}</td>
        <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-900">${getPriorityText(message.priority)}</td>
        <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-900">${message.retryCount || 0}</td>
        <td class="px-6 py-4 text-sm text-gray-900 max-w-xs truncate" title="${message.errorReason || '-'}">${message.errorReason || '-'}</td>
        <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-500">${AdminUtils.formatDate(message.deadLetteredAt)}</td>
        <td class="px-6 py-4 whitespace-nowrap">${statusBadge}</td>
        <td class="px-6 py-4 whitespace-nowrap text-sm font-medium">
            ${!message.isProcessed ? `
                <button onclick="retryDeadLetter(${message.id})" class="text-green-600 hover:text-green-900 mr-3">重试</button>
                <button onclick="dismissDeadLetter(${message.id})" class="text-gray-600 hover:text-gray-900 mr-3">标记</button>
            ` : ''}
            <button onclick="deleteDeadLetter(${message.id})" class="text-red-600 hover:text-red-900">删除</button>
        </td>
    `;

    return row;
}

function getPriorityText(priority) {
    switch (priority) {
        case 0:
            return '<span class="text-red-600 font-medium">高</span>';
        case 1:
            return '<span class="text-blue-600 font-medium">中</span>';
        case 2:
            return '<span class="text-gray-600">低</span>';
        default:
            return '-';
    }
}

// 重试单条死信消息
async function retryDeadLetter(id) {
    const confirmed = await Modal.confirm('确定要重试这条死信消息吗？');
    if (!confirmed) return;

    try {
        const result = await QueueAPI.retryDeadLetter(id);
        if (result.success) {
            AdminUtils.showToast('消息已重新发送到队列', 'success');
            await loadDeadLetters();
            await loadQueueStatus();
        }
    } catch (error) {
        console.error('重试失败:', error);
        AdminUtils.showToast(error.message || '重试失败', 'error');
    }
}

// 批量重试
async function retryBatch() {
    const confirmed = await Modal.confirm('确定要批量重试所有未处理的死信消息吗？');
    if (!confirmed) return;

    try {
        const result = await QueueAPI.retryBatch(10);
        if (result.success) {
            AdminUtils.showToast(`批量重试完成，成功${result.successCount}条，失败${result.failCount}条`, 'success');
            await loadDeadLetters();
            await loadQueueStatus();
        }
    } catch (error) {
        console.error('批量重试失败:', error);
        AdminUtils.showToast(error.message || '批量重试失败', 'error');
    }
}

// 标记为已处理
async function dismissDeadLetter(id) {
    const note = prompt('请输入处理备注（可选）:');
    if (note === null) return;

    try {
        const result = await QueueAPI.dismissDeadLetter(id, note);
        if (result.success) {
            AdminUtils.showToast('已标记为已处理', 'success');
            await loadDeadLetters();
        }
    } catch (error) {
        console.error('标记失败:', error);
        AdminUtils.showToast(error.message || '标记失败', 'error');
    }
}

// 删除死信消息
async function deleteDeadLetter(id) {
    const confirmed = await Modal.confirm('确定要删除这条死信消息记录吗？此操作不可恢复！');
    if (!confirmed) return;

    try {
        const result = await QueueAPI.deleteDeadLetter(id);
        if (result.success) {
            AdminUtils.showToast('删除成功', 'success');
            await loadDeadLetters();
        }
    } catch (error) {
        console.error('删除失败:', error);
        AdminUtils.showToast(error.message || '删除失败', 'error');
    }
}
