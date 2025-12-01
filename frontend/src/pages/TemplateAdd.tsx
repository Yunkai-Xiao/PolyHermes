import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Form, Input, Button, Radio, InputNumber, Switch, message, Typography, Space } from 'antd'
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'

const { Title } = Typography

const TemplateAdd: React.FC = () => {
  const navigate = useNavigate()
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED'>('RATIO')
  
  const handleSubmit = async (values: any) => {
    // 前端校验：如果填写了 minOrderSize，必须 >= 1
    if (values.copyMode === 'RATIO' && values.minOrderSize !== undefined && values.minOrderSize !== null && values.minOrderSize !== '' && Number(values.minOrderSize) < 1) {
      message.error('最小金额必须 >= 1')
      return
    }
    
    // 前端校验：固定金额模式下，fixedAmount 必填且必须 >= 1
    if (values.copyMode === 'FIXED') {
      const fixedAmount = values.fixedAmount
      if (fixedAmount === undefined || fixedAmount === null || fixedAmount === '') {
        message.error('请输入固定跟单金额')
        return
      }
      const amount = Number(fixedAmount)
      if (isNaN(amount)) {
        message.error('请输入有效的数字')
        return
      }
      if (amount < 1) {
        message.error('固定金额必须 >= 1，请重新输入')
        return
      }
    }
    
    setLoading(true)
    try {
      const response = await apiService.templates.create({
        templateName: values.templateName,
        copyMode: values.copyMode || 'RATIO',
        // 将百分比转换为小数：100% -> 1.0
        copyRatio: values.copyMode === 'RATIO' && values.copyRatio ? (values.copyRatio / 100).toString() : undefined,
        fixedAmount: values.copyMode === 'FIXED' ? values.fixedAmount?.toString() : undefined,
        maxOrderSize: values.copyMode === 'RATIO' ? values.maxOrderSize?.toString() : undefined,
        minOrderSize: values.copyMode === 'RATIO' ? values.minOrderSize?.toString() : undefined,
        maxDailyOrders: values.maxDailyOrders,
        priceTolerance: values.priceTolerance?.toString(),
        supportSell: values.supportSell !== false
      })
      
      if (response.data.code === 0) {
        message.success('创建模板成功')
        navigate('/templates')
      } else {
        message.error(response.data.msg || '创建模板失败')
      }
    } catch (error: any) {
      message.error(error.message || '创建模板失败')
    } finally {
      setLoading(false)
    }
  }
  
  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/templates')}
        >
          返回
        </Button>
      </div>
      
      <Card>
        <Title level={4}>创建跟单模板</Title>
        
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          initialValues={{
            copyMode: 'RATIO',
            copyRatio: 100, // 默认 100%（显示为百分比）
            maxOrderSize: 1000,
            minOrderSize: 1,
            maxDailyOrders: 100,
            priceTolerance: 5,
            supportSell: true
          }}
        >
          <Form.Item
            label="模板名称"
            name="templateName"
            tooltip="模板的唯一标识名称，用于区分不同的跟单配置模板。模板名称必须唯一，不能与其他模板重名。"
            rules={[{ required: true, message: '请输入模板名称' }]}
          >
            <Input placeholder="请输入模板名称" />
          </Form.Item>
          
          <Form.Item
            label="跟单金额模式"
            name="copyMode"
            tooltip="选择跟单金额的计算方式。比例模式：跟单金额随 Leader 订单大小按比例变化；固定金额模式：无论 Leader 订单大小如何，跟单金额都固定不变。"
            rules={[{ required: true }]}
          >
            <Radio.Group onChange={(e) => setCopyMode(e.target.value)}>
              <Radio value="RATIO">比例模式</Radio>
              <Radio value="FIXED">固定金额模式</Radio>
            </Radio.Group>
          </Form.Item>
          
          {copyMode === 'RATIO' && (
            <Form.Item
              label="跟单比例"
              name="copyRatio"
              tooltip="跟单比例表示跟单金额相对于 Leader 订单金额的百分比。例如：100% 表示 1:1 跟单，50% 表示半仓跟单，200% 表示双倍跟单"
            >
              <InputNumber
                min={10}
                max={1000}
                step={1}
                precision={0}
                style={{ width: '100%' }}
                addonAfter="%"
                placeholder="例如：100 表示 100%（1:1 跟单），默认 100%"
                parser={(value) => {
                  const parsed = parseFloat(value || '0')
                  if (parsed > 1000) return 1000
                  return parsed
                }}
                formatter={(value) => {
                  if (!value) return ''
                  const num = parseFloat(value.toString())
                  if (num > 1000) return '1000'
                  return value.toString()
                }}
              />
            </Form.Item>
          )}
          
          {copyMode === 'FIXED' && (
            <Form.Item
              label="固定跟单金额 (USDC)"
              name="fixedAmount"
              rules={[
                { required: true, message: '请输入固定跟单金额' },
                { 
                  validator: (_, value) => {
                    // required 已经处理了空值情况，这里只处理非空值的校验
                    if (value !== undefined && value !== null && value !== '') {
                      const amount = Number(value)
                      if (isNaN(amount)) {
                        return Promise.reject(new Error('请输入有效的数字'))
                      }
                      if (amount < 1) {
                        return Promise.reject(new Error('固定金额必须 >= 1，请重新输入'))
                      }
                    }
                    return Promise.resolve()
                  }
                }
              ]}
            >
              <InputNumber
                step={0.0001}
                precision={4}
                style={{ width: '100%' }}
                placeholder="固定金额，不随 Leader 订单大小变化，必须 >= 1"
              />
            </Form.Item>
          )}
          
          {copyMode === 'RATIO' && (
            <>
              <Form.Item
                label="单笔订单最大金额 (USDC)"
                name="maxOrderSize"
                tooltip="比例模式下，限制单笔跟单订单的最大金额上限，用于防止跟单金额过大，控制风险。例如：设置为 1000，即使计算出的跟单金额超过 1000，也会限制为 1000 USDC。"
              >
                <InputNumber
                  min={0.0001}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder="仅在比例模式下生效（可选）"
                />
              </Form.Item>
              
              <Form.Item
                label="单笔订单最小金额 (USDC)"
                name="minOrderSize"
                tooltip="比例模式下，限制单笔跟单订单的最小金额下限，用于过滤掉金额过小的订单，避免频繁小额交易。如果填写，必须 >= 1 USDC。例如：设置为 10，如果计算出的跟单金额小于 10，则跳过该订单。"
                rules={[
                  { 
                    validator: (_, value) => {
                      if (value === undefined || value === null || value === '') {
                        return Promise.resolve() // 可选字段，允许为空
                      }
                      if (typeof value === 'number' && value < 1) {
                        return Promise.reject(new Error('最小金额必须 >= 1'))
                      }
                      return Promise.resolve()
                    }
                  }
                ]}
              >
                <InputNumber
                  min={1}
                  step={0.0001}
                  precision={4}
                  style={{ width: '100%' }}
                  placeholder="仅在比例模式下生效，必须 >= 1（可选）"
                />
              </Form.Item>
            </>
          )}
          
          <Form.Item
            label="每日最大跟单订单数"
            name="maxDailyOrders"
            tooltip="限制每日最多跟单的订单数量，用于风险控制，防止过度交易。例如：设置为 50，当日跟单订单数达到 50 后，停止跟单，次日重置。"
          >
            <InputNumber
              min={1}
              step={1}
              style={{ width: '100%' }}
              placeholder="默认 100（可选）"
            />
          </Form.Item>
          
          <Form.Item
            label="价格容忍度 (%)"
            name="priceTolerance"
            tooltip="允许跟单价格在 Leader 价格基础上的调整范围，用于在 Leader 价格 ± 容忍度范围内调整价格，提高成交率。例如：设置为 5%，Leader 价格为 0.5，则跟单价格可在 0.475-0.525 范围内。"
          >
            <InputNumber
              min={0}
              max={100}
              step={0.1}
              precision={2}
              style={{ width: '100%' }}
              placeholder="默认 5%（可选）"
            />
          </Form.Item>
          
          <Form.Item
            label="跟单卖出"
            name="supportSell"
            tooltip="是否跟单 Leader 的卖出订单。开启：跟单 Leader 的买入和卖出订单；关闭：只跟单 Leader 的买入订单，忽略卖出订单。"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          
          <Form.Item shouldUpdate>
            {({ getFieldsError }) => {
              const errors = getFieldsError()
              const hasErrors = errors.some(({ errors }) => errors && errors.length > 0)
              return (
                <Space>
                  <Button
                    type="primary"
                    htmlType="submit"
                    icon={<SaveOutlined />}
                    loading={loading}
                    disabled={hasErrors}
                  >
                    创建模板
                  </Button>
                  <Button onClick={() => navigate('/templates')}>
                    取消
                  </Button>
                </Space>
              )
            }}
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}

export default TemplateAdd

