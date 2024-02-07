package com.greedy.section03.sync;

public class Consumer extends Thread{

	/* 공유 자원을 꺼내서 사용하는 소비자 */
	
	private Buffer buffer;
	
	public Consumer(Buffer buffer) {
		this.buffer = buffer;
	}
	
	@Override
	public void run() {
		for(int i = 0; i <= 10; i++) {
			buffer.getData();
			
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}
