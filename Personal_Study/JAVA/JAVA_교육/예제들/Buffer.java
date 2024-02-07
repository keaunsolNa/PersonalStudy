package com.greedy.section03.sync;

public class Buffer {

	private int data;
	private boolean empty = true;
	
	public synchronized void getData() {
		
		/* 초기 empty가 true인 동안은 무한루프를 돌며 wait()를 반복 실행 */
		while(empty) {
			try {
				/* notify()가 되기 전까지 일시정지 상태로 기다리는 상태 */
				wait();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		} 
		
		/* 생산자에 의해 data 값이 변경되고 empty값이 false로 바뀌면 반복문을 빠져 나온다. */ 
		System.out.println("소비자: " + data + "번 상품 소비하였습니다.");

		/* 값이 있으면 소비하고, 다시 empty는 true로 변경된다. */
		empty = true;
		
		/* 스레드를 다시 실행 대기 상태로 만든다. */
		notify();
	}
	
	public synchronized void setData(int data) {
	
		/* empty가 false인 동안은 무한루프를 돌며 wait()를 반복 실행 */
		while(!empty) {
			try {
				wait();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		/* data를 변경 */
		this.data = data;
		System.out.println("생산자: " + data + "번 상품 생산하였습니다.");
		
		/* empty를 false로 변경 */
		empty = false;
		
		/* 스레드를 다시 실행 대기 상태로 만든다. */
		notify();
	}
}
