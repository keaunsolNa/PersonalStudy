package com.greedy.section03.sync;

public class Producer extends Thread{

	/* 공유 자원을 넣는 일을 하는 생산자 */
	
	private Buffer buffer;
	
	public Producer(Buffer buffer) {
		this.buffer = buffer;
	}
	
	@Override
	public void run() {
		for(int i = 0 ; i < 10; i++) {
			buffer.setData(i);
			
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}
